/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.join.filter;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.math.expr.Expr;
import org.apache.druid.query.filter.Filter;
import org.apache.druid.query.filter.InDimFilter;
import org.apache.druid.query.filter.ValueMatcher;
import org.apache.druid.segment.ColumnSelectorFactory;
import org.apache.druid.segment.VirtualColumn;
import org.apache.druid.segment.VirtualColumns;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.filter.Filters;
import org.apache.druid.segment.filter.OrFilter;
import org.apache.druid.segment.filter.SelectorFilter;
import org.apache.druid.segment.join.Equality;
import org.apache.druid.segment.join.JoinConditionAnalysis;
import org.apache.druid.segment.join.JoinableClause;
import org.apache.druid.segment.virtual.ExpressionVirtualColumn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * When there is a filter in a join query, we can sometimes improve performance by applying parts of the filter
 * when we first read from the base table instead of after the join.
 * 
 * The first step of the filter splitting is to convert the filter into
 * https://en.wikipedia.org/wiki/Conjunctive_normal_form (an AND of ORs). This allows us to consider each
 * OR clause independently as a candidate for filter push down to the base table.
 * 
 * A filter clause can be pushed down if it meets one of the following conditions:
 * - The filter only applies to columns from the base table
 * - The filter applies to columns from the join table, and we determine that the filter can be rewritten
 * into a filter on columns from the base table
 * 
 * For the second case, where we rewrite filter clauses, the rewritten clause can be less selective than the original,
 * so we preserve the original clause in the post-join filtering phase.
 * 
 * The starting point for join analysis is the {@link #computeJoinFilterPreAnalysis} method. This method should be
 * called before performing any per-segment join query work. This method converts the query filter into
 * conjunctive normal form, and splits the CNF clauses into a portion that only references base table columns and
 * a portion that references join table columns. For the filter clauses that apply to join table columns, the
 * pre-analysis step computes the information necessary for rewriting such filters into filters on base table columns.
 * 
 * The result of this pre-analysis method should be passed into the next step of join filter analysis, described below.
 * 
 * The {@link #splitFilter(JoinFilterPreAnalysis)} method takes the pre-analysis result and optionally applies the
 * filter rewrite and push down operations on a per-segment level.
 */
public class JoinFilterAnalyzer
{
  private static final String PUSH_DOWN_VIRTUAL_COLUMN_NAME_BASE = "JOIN-FILTER-PUSHDOWN-VIRTUAL-COLUMN-";
  private static final ColumnSelectorFactory ALL_NULL_COLUMN_SELECTOR_FACTORY = new AllNullColumnSelectorFactory();

  /**
   * Before making per-segment filter splitting decisions, we first do a pre-analysis step
   * where we convert the query filter (if any) into conjunctive normal form and then
   * determine the structure of RHS filter rewrites (if any), since this information is shared across all
   * per-segment operations.
   * 
   * See {@link JoinFilterPreAnalysis} for details on the result of this pre-analysis step.
   *
   * @param joinableClauses                 The joinable clauses from the query
   * @param virtualColumns                  The virtual columns from the query
   * @param originalFilter                  The original filter from the query
   * @param enableFilterPushDown            Whether to enable filter push down
   * @param enableFilterRewrite             Whether to enable rewrites of filters involving RHS columns
   * @param enableRewriteValueColumnFilters Whether to enable rewrites of filters invovling RHS non-key columns
   * @param filterRewriteMaxSize            The maximum size of the correlated value set for rewritten filters.
   *                                        If the correlated value set size exceeds this, the filter will not be
   *                                        rewritten and pushed down.
   *
   * @return A JoinFilterPreAnalysis containing information determined in this pre-analysis step.
   */
  public static JoinFilterPreAnalysis computeJoinFilterPreAnalysis(
      List<JoinableClause> joinableClauses,
      VirtualColumns virtualColumns,
      Filter originalFilter,
      boolean enableFilterPushDown,
      boolean enableFilterRewrite,
      boolean enableRewriteValueColumnFilters,
      long filterRewriteMaxSize
  )
  {
    final List<VirtualColumn> preJoinVirtualColumns = new ArrayList<>();
    final List<VirtualColumn> postJoinVirtualColumns = new ArrayList<>();

    splitVirtualColumns(joinableClauses, virtualColumns, preJoinVirtualColumns, postJoinVirtualColumns);
    JoinFilterPreAnalysis.Builder preAnalysisBuilder =
        new JoinFilterPreAnalysis.Builder(joinableClauses, originalFilter, postJoinVirtualColumns)
            .withEnableFilterPushDown(enableFilterPushDown)
            .withEnableFilterRewrite(enableFilterRewrite);
    if (originalFilter == null || !enableFilterPushDown) {
      return preAnalysisBuilder.build();
    }

    Set<Filter> normalizedOrClauses = Filters.toNormalizedOrClauses(originalFilter);

    List<Filter> normalizedBaseTableClauses = new ArrayList<>();
    List<Filter> normalizedJoinTableClauses = new ArrayList<>();

    for (Filter orClause : normalizedOrClauses) {
      Set<String> reqColumns = orClause.getRequiredColumns();
      if (areSomeColumnsFromJoin(joinableClauses, reqColumns) || areSomeColumnsFromPostJoinVirtualColumns(
          postJoinVirtualColumns,
          reqColumns
      )) {
        normalizedJoinTableClauses.add(orClause);
      } else {
        normalizedBaseTableClauses.add(orClause);
      }
    }
    preAnalysisBuilder
        .withNormalizedBaseTableClauses(normalizedBaseTableClauses)
        .withNormalizedJoinTableClauses(normalizedJoinTableClauses);
    if (!enableFilterRewrite) {
      return preAnalysisBuilder.build();
    }

    // build the equicondition map, used for determining how the tables are connected through joins
    Map<String, Set<Expr>> equiconditions = preAnalysisBuilder.computeEquiconditionsFromJoinableClauses();

    Set<RhsRewriteCandidate> rhsRewriteCandidates = getRhsRewriteCandidates(normalizedJoinTableClauses, equiconditions, joinableClauses);

    // Build a map of RHS table prefix -> JoinFilterColumnCorrelationAnalysis based on the RHS rewrite candidates
    Map<String, Optional<Map<String, JoinFilterColumnCorrelationAnalysis>>> correlationsByPrefix = new HashMap<>();
    Map<String, Optional<JoinFilterColumnCorrelationAnalysis>> directRewriteCorrelations = new HashMap<>();

    for (RhsRewriteCandidate rhsRewriteCandidate : rhsRewriteCandidates) {
      if (rhsRewriteCandidate.isDirectRewrite()) {
        directRewriteCorrelations.computeIfAbsent(
            rhsRewriteCandidate.getRhsColumn(),
            c -> {
              Optional<Map<String, JoinFilterColumnCorrelationAnalysis>> correlatedBaseTableColumns =
                  findCorrelatedBaseTableColumns(
                      joinableClauses,
                      c,
                      rhsRewriteCandidate,
                      equiconditions
                  );
              if (!correlatedBaseTableColumns.isPresent()) {
                return Optional.empty();
              } else {
                JoinFilterColumnCorrelationAnalysis baseColumnAnalysis = correlatedBaseTableColumns.get().get(c);
                // for direct rewrites, there will only be one analysis keyed by the RHS column
                assert (baseColumnAnalysis != null);
                return Optional.of(correlatedBaseTableColumns.get().get(c));
              }
            }
        );
      } else {
        correlationsByPrefix.computeIfAbsent(
            rhsRewriteCandidate.getJoinableClause().getPrefix(),
            p -> findCorrelatedBaseTableColumns(
                joinableClauses,
                p,
                rhsRewriteCandidate,
                equiconditions
            )
        );
      }
    }

    // Using the RHS table prefix -> JoinFilterColumnCorrelationAnalysis created in the previous step,
    // build a map of rhsFilterColumn -> Pair(rhsFilterColumn, rhsFilterValue) -> correlatedValues for specific filter pair
    // The Pair(rhsFilterColumn, rhsFilterValue) -> correlatedValues mappings are stored in the
    // JoinFilterColumnCorrelationAnalysis objects, which are shared across all rhsFilterColumn entries that belong
    // to the same RHS table.
    //
    // The value is a List<JoinFilterColumnCorrelationAnalysis> instead of a single value because a table can be joined
    // to another via multiple columns.
    // (See JoinFilterAnalyzerTest.test_filterPushDown_factToRegionOneColumnToTwoRHSColumnsAndFilterOnRHS for an example)
    Map<String, List<JoinFilterColumnCorrelationAnalysis>> correlationsByFilteringColumn = new LinkedHashMap<>();
    Map<String, List<JoinFilterColumnCorrelationAnalysis>> correlationsByDirectFilteringColumn = new LinkedHashMap<>();
    for (RhsRewriteCandidate rhsRewriteCandidate : rhsRewriteCandidates) {
      if (rhsRewriteCandidate.isDirectRewrite()) {
        List<JoinFilterColumnCorrelationAnalysis> perColumnCorrelations =
            correlationsByDirectFilteringColumn.computeIfAbsent(
                rhsRewriteCandidate.getRhsColumn(),
                (rhsCol) -> new ArrayList<>()
            );
        perColumnCorrelations.add(
            directRewriteCorrelations.get(rhsRewriteCandidate.getRhsColumn()).get()
        );
        continue;
      }

      Optional<Map<String, JoinFilterColumnCorrelationAnalysis>> correlationsForPrefix = correlationsByPrefix.get(
          rhsRewriteCandidate.getJoinableClause().getPrefix()
      );
      if (correlationsForPrefix.isPresent()) {
        for (Map.Entry<String, JoinFilterColumnCorrelationAnalysis> correlationForPrefix : correlationsForPrefix.get()
                                                                                                                .entrySet()) {
          List<JoinFilterColumnCorrelationAnalysis> perColumnCorrelations =
              correlationsByFilteringColumn.computeIfAbsent(
                  rhsRewriteCandidate.getRhsColumn(),
                  (rhsCol) -> new ArrayList<>()
              );
          perColumnCorrelations.add(correlationForPrefix.getValue());
          correlationForPrefix.getValue().getCorrelatedValuesMap().computeIfAbsent(
              Pair.of(rhsRewriteCandidate.getRhsColumn(), rhsRewriteCandidate.getValueForRewrite()),
              (rhsVal) -> {
                Set<String> correlatedValues = getCorrelatedValuesForPushDown(
                    rhsRewriteCandidate.getRhsColumn(),
                    rhsRewriteCandidate.getValueForRewrite(),
                    correlationForPrefix.getValue().getJoinColumn(),
                    rhsRewriteCandidate.getJoinableClause(),
                    enableRewriteValueColumnFilters,
                    filterRewriteMaxSize
                );

                if (correlatedValues.isEmpty()) {
                  return Optional.empty();
                } else {
                  return Optional.of(correlatedValues);
                }
              }
          );
        }
      } else {
        correlationsByFilteringColumn.put(rhsRewriteCandidate.getRhsColumn(), null);
      }
    }

    // Go through each per-column analysis list and prune duplicates
    for (Map.Entry<String, List<JoinFilterColumnCorrelationAnalysis>> correlation : correlationsByFilteringColumn
        .entrySet()) {
      if (correlation.getValue() != null) {
        List<JoinFilterColumnCorrelationAnalysis> dedupList = eliminateCorrelationDuplicates(
            correlation.getValue()
        );
        correlationsByFilteringColumn.put(correlation.getKey(), dedupList);
      }
    }
    for (Map.Entry<String, List<JoinFilterColumnCorrelationAnalysis>> correlation : correlationsByDirectFilteringColumn
        .entrySet()) {
      if (correlation.getValue() != null) {
        List<JoinFilterColumnCorrelationAnalysis> dedupList = eliminateCorrelationDuplicates(
            correlation.getValue()
        );
        correlationsByDirectFilteringColumn.put(correlation.getKey(), dedupList);
      }
    }
    preAnalysisBuilder.withCorrelationsByFilteringColumn(correlationsByFilteringColumn)
                      .withCorrelationsByDirectFilteringColumn(correlationsByDirectFilteringColumn);

    return preAnalysisBuilder.build();
  }

  private static Optional<RhsRewriteCandidate> determineRhsRewriteCandidatesForSingleFilter(
      Filter orClause,
      Map<String, Set<Expr>> equiconditions,
      List<JoinableClause> joinableClauses
  )
  {
    // Check if the filter clause is on the RHS join column. If so, we can rewrite the clause to filter on the
    // LHS join column instead.
    // Currently, we only support rewrites of filters that operate on a single column for simplicity.
    Set<String> requiredColumns = orClause.getRequiredColumns();
    if (orClause.supportsRequiredColumnRewrite() &&
        doesRequiredColumnSetSupportDirectJoinFilterRewrite(requiredColumns, equiconditions)) {
      String reqColumn = requiredColumns.iterator().next();
      JoinableClause joinableClause = isColumnFromJoin(joinableClauses, reqColumn);

      return Optional.of(
          new RhsRewriteCandidate(
              joinableClause,
              reqColumn,
              null,
              true
          )
      );
    } else if (orClause instanceof SelectorFilter) {
      // this is a candidate for RHS filter rewrite, determine column correlations and correlated values
      String reqColumn = ((SelectorFilter) orClause).getDimension();
      String reqValue = ((SelectorFilter) orClause).getValue();
      JoinableClause joinableClause = isColumnFromJoin(joinableClauses, reqColumn);
      if (joinableClause != null) {
        return Optional.of(
            new RhsRewriteCandidate(
                joinableClause,
                reqColumn,
                reqValue,
                false
            )
        );
      }
    }

    return Optional.empty();
  }

  private static boolean doesRequiredColumnSetSupportDirectJoinFilterRewrite(
      Set<String> requiredColumns,
      Map<String, Set<Expr>> equiconditions
  )
  {
    if (requiredColumns.size() == 1) {
      String reqColumn = requiredColumns.iterator().next();
      return equiconditions.containsKey(reqColumn);
    }
    return false;
  }

  /**
   * @param joinFilterPreAnalysis The pre-analysis computed by {@link #computeJoinFilterPreAnalysis)}
   *
   * @return A JoinFilterSplit indicating what parts of the filter should be applied pre-join and post-join
   */
  public static JoinFilterSplit splitFilter(
      JoinFilterPreAnalysis joinFilterPreAnalysis
  )
  {
    if (joinFilterPreAnalysis.getOriginalFilter() == null || !joinFilterPreAnalysis.isEnableFilterPushDown()) {
      return new JoinFilterSplit(
          null,
          joinFilterPreAnalysis.getOriginalFilter(),
          ImmutableSet.of()
      );
    }

    // Pushdown filters, rewriting if necessary
    List<Filter> leftFilters = new ArrayList<>();
    List<Filter> rightFilters = new ArrayList<>();
    Map<Expr, VirtualColumn> pushDownVirtualColumnsForLhsExprs = new HashMap<>();

    for (Filter baseTableFilter : joinFilterPreAnalysis.getNormalizedBaseTableClauses()) {
      if (!filterMatchesNull(baseTableFilter)) {
        leftFilters.add(baseTableFilter);
      } else {
        rightFilters.add(baseTableFilter);
      }
    }

    for (Filter orClause : joinFilterPreAnalysis.getNormalizedJoinTableClauses()) {
      JoinFilterAnalysis joinFilterAnalysis = analyzeJoinFilterClause(
          orClause,
          joinFilterPreAnalysis,
          pushDownVirtualColumnsForLhsExprs
      );
      if (joinFilterAnalysis.isCanPushDown()) {
        //noinspection OptionalGetWithoutIsPresent isCanPushDown checks isPresent
        leftFilters.add(joinFilterAnalysis.getPushDownFilter().get());
      }
      if (joinFilterAnalysis.isRetainAfterJoin()) {
        rightFilters.add(joinFilterAnalysis.getOriginalFilter());
      }
    }

    return new JoinFilterSplit(
        Filters.and(leftFilters),
        Filters.and(rightFilters),
        new HashSet<>(pushDownVirtualColumnsForLhsExprs.values())
    );
  }


  /**
   * Analyze a filter clause from a filter that is in conjunctive normal form (AND of ORs).
   * The clause is expected to be an OR filter or a leaf filter.
   *
   * @param filterClause                       Individual filter clause (an OR filter or a leaf filter) from a filter that is in CNF
   * @param joinFilterPreAnalysis              The pre-analysis computed by {@link #computeJoinFilterPreAnalysis)}
   * @param pushDownVirtualColumnsForLhsExprs  Used when there are LHS expressions in the join equiconditions.
   *                                           If we rewrite an RHS filter such that it applies to the LHS expression instead,
   *                                           because the expression existed only in the equicondition, we must create a virtual column
   *                                           on the LHS with the same expression in order to apply the filter.
   *                                           The specific rewriting methods such as {@link #rewriteSelectorFilter} will use this
   *                                           as a cache for virtual columns that they need to created, keyed by the expression, so that
   *                                           they can avoid creating redundant virtual columns.
   *
   *
   * @return a JoinFilterAnalysis that contains a possible filter rewrite and information on how to handle the filter.
   */
  private static JoinFilterAnalysis analyzeJoinFilterClause(
      Filter filterClause,
      JoinFilterPreAnalysis joinFilterPreAnalysis,
      Map<Expr, VirtualColumn> pushDownVirtualColumnsForLhsExprs
  )
  {
    // NULL matching conditions are not currently pushed down.
    // They require special consideration based on the join type, and for simplicity of the initial implementation
    // this is not currently handled.
    if (!joinFilterPreAnalysis.isEnableFilterRewrite() || filterMatchesNull(filterClause)) {
      return JoinFilterAnalysis.createNoPushdownFilterAnalysis(filterClause);
    }

    if (filterClause instanceof OrFilter) {
      return rewriteOrFilter(
          (OrFilter) filterClause,
          joinFilterPreAnalysis,
          pushDownVirtualColumnsForLhsExprs
      );
    }

    if (filterClause.supportsRequiredColumnRewrite() && doesRequiredColumnSetSupportDirectJoinFilterRewrite(
        filterClause.getRequiredColumns(),
        joinFilterPreAnalysis.getEquiconditions()
    )) {
      return rewriteFilterDirect(
          filterClause,
          joinFilterPreAnalysis,
          pushDownVirtualColumnsForLhsExprs
      );
    }

    // Currently we only support rewrites of selector filters and selector filters within OR filters.
    if (filterClause instanceof SelectorFilter) {
      return rewriteSelectorFilter(
          (SelectorFilter) filterClause,
          joinFilterPreAnalysis,
          pushDownVirtualColumnsForLhsExprs
      );
    }

    return JoinFilterAnalysis.createNoPushdownFilterAnalysis(filterClause);
  }

  private static JoinFilterAnalysis rewriteFilterDirect(
      Filter filterClause,
      JoinFilterPreAnalysis joinFilterPreAnalysis,
      Map<Expr, VirtualColumn> pushDownVirtualColumnsForLhsExprs
  )
  {
    if (!filterClause.supportsRequiredColumnRewrite()) {
      return JoinFilterAnalysis.createNoPushdownFilterAnalysis(filterClause);
    }

    List<Filter> newFilters = new ArrayList<>();

    // we only support direct rewrites of filters that reference a single column
    String reqColumn = filterClause.getRequiredColumns().iterator().next();

    List<JoinFilterColumnCorrelationAnalysis> correlationAnalyses = joinFilterPreAnalysis.getCorrelationsByDirectFilteringColumn()
                                                                                         .get(reqColumn);

    if (correlationAnalyses == null) {
      return JoinFilterAnalysis.createNoPushdownFilterAnalysis(filterClause);
    }

    for (JoinFilterColumnCorrelationAnalysis correlationAnalysis : correlationAnalyses) {
      if (correlationAnalysis.supportsPushDown()) {
        for (String correlatedBaseColumn : correlationAnalysis.getBaseColumns()) {
          Filter rewrittenFilter = filterClause.rewriteRequiredColumns(ImmutableMap.of(
              reqColumn,
              correlatedBaseColumn
          ));
          newFilters.add(rewrittenFilter);
        }

        for (Expr correlatedBaseExpr : correlationAnalysis.getBaseExpressions()) {
          // We need to create a virtual column for the expressions when pushing down
          VirtualColumn pushDownVirtualColumn = pushDownVirtualColumnsForLhsExprs.computeIfAbsent(
              correlatedBaseExpr,
              (expr) -> {
                String vcName = getCorrelatedBaseExprVirtualColumnName(pushDownVirtualColumnsForLhsExprs.size());
                return new ExpressionVirtualColumn(
                    vcName,
                    correlatedBaseExpr,
                    ValueType.STRING
                );
              }
          );

          Filter rewrittenFilter = filterClause.rewriteRequiredColumns(ImmutableMap.of(
              reqColumn,
              pushDownVirtualColumn.getOutputName()
          ));
          newFilters.add(rewrittenFilter);
        }
      }
    }

    if (newFilters.isEmpty()) {
      return JoinFilterAnalysis.createNoPushdownFilterAnalysis(filterClause);
    }

    return new JoinFilterAnalysis(
        false,
        filterClause,
        Filters.and(newFilters)
    );
  }

  /**
   * Potentially rewrite the subfilters of an OR filter so that the whole OR filter can be pushed down to
   * the base table.
   *
   * @param orFilter              OrFilter to be rewritten
   * @param joinFilterPreAnalysis The pre-analysis computed by {@link #computeJoinFilterPreAnalysis)}
   * @param pushDownVirtualColumnsForLhsExprs See comments on {@link #analyzeJoinFilterClause}
   * @return A JoinFilterAnalysis indicating how to handle the potentially rewritten filter
   */
  private static JoinFilterAnalysis rewriteOrFilter(
      OrFilter orFilter,
      JoinFilterPreAnalysis joinFilterPreAnalysis,
      Map<Expr, VirtualColumn> pushDownVirtualColumnsForLhsExprs
  )
  {
    Set<Filter> newFilters = new HashSet<>();
    boolean retainRhs = false;

    for (Filter filter : orFilter.getFilters()) {
      if (!areSomeColumnsFromJoin(joinFilterPreAnalysis.getJoinableClauses(), filter.getRequiredColumns())) {
        newFilters.add(filter);
        continue;
      }

      JoinFilterAnalysis rewritten = null;
      if (doesRequiredColumnSetSupportDirectJoinFilterRewrite(
          filter.getRequiredColumns(),
          joinFilterPreAnalysis.getEquiconditions()
      )) {
        rewritten = rewriteFilterDirect(
            filter,
            joinFilterPreAnalysis,
            pushDownVirtualColumnsForLhsExprs
        );
      } else if (filter instanceof SelectorFilter) {
        retainRhs = true;
        // We could optimize retainRhs handling further by introducing a "filter to retain" property to the
        // analysis, and only keeping the subfilters that need to be retained
        rewritten = rewriteSelectorFilter(
            (SelectorFilter) filter,
            joinFilterPreAnalysis,
            pushDownVirtualColumnsForLhsExprs
        );
      }

      if (rewritten == null || !rewritten.isCanPushDown()) {
        return JoinFilterAnalysis.createNoPushdownFilterAnalysis(orFilter);
      } else {
        //noinspection OptionalGetWithoutIsPresent isCanPushDown checks isPresent
        newFilters.add(rewritten.getPushDownFilter().get());
      }
    }

    return new JoinFilterAnalysis(
        retainRhs,
        orFilter,
        Filters.or(newFilters)
    );
  }

  /**
   * Rewrites a selector filter on a join table into an IN filter on the base table.
   *
   * @param selectorFilter        SelectorFilter to be rewritten
   * @param joinFilterPreAnalysis The pre-analysis computed by {@link #computeJoinFilterPreAnalysis)}
   * @param pushDownVirtualColumnsForLhsExprs See comments on {@link #analyzeJoinFilterClause}
   * @return A JoinFilterAnalysis that indicates how to handle the potentially rewritten filter
   */
  private static JoinFilterAnalysis rewriteSelectorFilter(
      SelectorFilter selectorFilter,
      JoinFilterPreAnalysis joinFilterPreAnalysis,
      Map<Expr, VirtualColumn> pushDownVirtualColumnsForLhsExprs
  )
  {
    List<Filter> newFilters = new ArrayList<>();

    String filteringColumn = selectorFilter.getDimension();
    String filteringValue = selectorFilter.getValue();

    if (areSomeColumnsFromPostJoinVirtualColumns(
        joinFilterPreAnalysis.getPostJoinVirtualColumns(),
        selectorFilter.getRequiredColumns()
    )) {
      return JoinFilterAnalysis.createNoPushdownFilterAnalysis(selectorFilter);
    }

    if (!areSomeColumnsFromJoin(joinFilterPreAnalysis.getJoinableClauses(), selectorFilter.getRequiredColumns())) {
      return new JoinFilterAnalysis(
          false,
          selectorFilter,
          selectorFilter
      );
    }

    List<JoinFilterColumnCorrelationAnalysis> correlationAnalyses = joinFilterPreAnalysis.getCorrelationsByFilteringColumn()
                                                                                         .get(filteringColumn);

    if (correlationAnalyses == null) {
      return JoinFilterAnalysis.createNoPushdownFilterAnalysis(selectorFilter);
    }

    for (JoinFilterColumnCorrelationAnalysis correlationAnalysis : correlationAnalyses) {
      if (correlationAnalysis.supportsPushDown()) {
        Optional<Set<String>> correlatedValues = correlationAnalysis.getCorrelatedValuesMap().get(
            Pair.of(filteringColumn, filteringValue)
        );

        if (!correlatedValues.isPresent()) {
          return JoinFilterAnalysis.createNoPushdownFilterAnalysis(selectorFilter);
        }

        for (String correlatedBaseColumn : correlationAnalysis.getBaseColumns()) {
          Filter rewrittenFilter = new InDimFilter(
              correlatedBaseColumn,
              correlatedValues.get(),
              null,
              null
          ).toFilter();
          newFilters.add(rewrittenFilter);
        }

        for (Expr correlatedBaseExpr : correlationAnalysis.getBaseExpressions()) {
          // We need to create a virtual column for the expressions when pushing down
          VirtualColumn pushDownVirtualColumn = pushDownVirtualColumnsForLhsExprs.computeIfAbsent(
              correlatedBaseExpr,
              (expr) -> {
                String vcName = getCorrelatedBaseExprVirtualColumnName(pushDownVirtualColumnsForLhsExprs.size());
                return new ExpressionVirtualColumn(
                    vcName,
                    correlatedBaseExpr,
                    ValueType.STRING
                );
              }
          );

          Filter rewrittenFilter = new InDimFilter(
              pushDownVirtualColumn.getOutputName(),
              correlatedValues.get(),
              null,
              null
          ).toFilter();
          newFilters.add(rewrittenFilter);
        }
      }
    }

    if (newFilters.isEmpty()) {
      return JoinFilterAnalysis.createNoPushdownFilterAnalysis(selectorFilter);
    }

    return new JoinFilterAnalysis(
        true,
        selectorFilter,
        Filters.and(newFilters)
    );
  }

  private static String getCorrelatedBaseExprVirtualColumnName(int counter)
  {
    // May want to have this check other column names to absolutely prevent name conflicts
    return PUSH_DOWN_VIRTUAL_COLUMN_NAME_BASE + counter;
  }

  /**
   * Helper method for rewriting filters on join table columns into filters on base table columns.
   *
   * @param filterColumn           A join table column that we're filtering on
   * @param filterValue            The value to filter on
   * @param correlatedJoinColumn   A join table column that appears as the RHS of an equicondition, which we can correlate
   *                               with a column on the base table
   * @param clauseForFilteredTable The joinable clause that corresponds to the join table being filtered on
   *
   * @return A list of values of the correlatedJoinColumn that appear in rows where filterColumn = filterValue
   * Returns an empty set if we cannot determine the correlated values.
   */
  private static Set<String> getCorrelatedValuesForPushDown(
      String filterColumn,
      String filterValue,
      String correlatedJoinColumn,
      JoinableClause clauseForFilteredTable,
      boolean enableRewriteValueColumnFilters,
      long filterRewriteMaxSize
  )
  {
    String filterColumnNoPrefix = filterColumn.substring(clauseForFilteredTable.getPrefix().length());
    String correlatedColumnNoPrefix = correlatedJoinColumn.substring(clauseForFilteredTable.getPrefix().length());

    return clauseForFilteredTable.getJoinable().getCorrelatedColumnValues(
        filterColumnNoPrefix,
        filterValue,
        correlatedColumnNoPrefix,
        filterRewriteMaxSize,
        enableRewriteValueColumnFilters
    );
  }

  /**
   * For each rhs column that appears in the equiconditions for a table's JoinableClause,
   * we try to determine what base table columns are related to the rhs column through the total set of equiconditions.
   * We do this by searching backwards through the chain of join equiconditions using the provided equicondition map.
   * 
   * For example, suppose we have 3 tables, A,B,C, joined with the following conditions, where A is the base table:
   * A.joinColumn == B.joinColumn
   * B.joinColum == C.joinColumn
   * 
   * We would determine that C.joinColumn is correlated with A.joinColumn: we first see that
   * C.joinColumn is linked to B.joinColumn which in turn is linked to A.joinColumn
   * 
   * Suppose we had the following join conditions instead:
   * f(A.joinColumn) == B.joinColumn
   * B.joinColum == C.joinColumn
   * In this case, the JoinFilterColumnCorrelationAnalysis for C.joinColumn would be linked to f(A.joinColumn).
   * 
   * Suppose we had the following join conditions instead:
   * A.joinColumn == B.joinColumn
   * f(B.joinColum) == C.joinColumn
   * 
   * Because we cannot reverse the function f() applied to the second table B in all cases,
   * we cannot relate C.joinColumn to A.joinColumn, and we would not generate a correlation for C.joinColumn
   *
   * @param joinableClauses     List of joinable clauses for the query
   * @param tablePrefix         Prefix for a join table
   * @param rhsRewriteCandidate RHS rewrite candidate that we find correlated base table columns for
   * @param equiConditions      Map of equiconditions, keyed by the right hand columns
   *
   * @return A list of correlatation analyses for the equicondition RHS columns that reside in the table associated with
   * the tablePrefix
   */
  private static Optional<Map<String, JoinFilterColumnCorrelationAnalysis>> findCorrelatedBaseTableColumns(
      List<JoinableClause> joinableClauses,
      String tablePrefix,
      RhsRewriteCandidate rhsRewriteCandidate,
      Map<String, Set<Expr>> equiConditions
  )
  {
    JoinableClause clauseForTablePrefix = rhsRewriteCandidate.getJoinableClause();
    JoinConditionAnalysis jca = clauseForTablePrefix.getCondition();

    Set<String> rhsColumns = new HashSet<>();
    if (rhsRewriteCandidate.isDirectRewrite()) {
      // If we filter on a RHS join column, we only need to consider that column from the RHS side
      rhsColumns.add(rhsRewriteCandidate.getRhsColumn());
    } else {
      for (Equality eq : jca.getEquiConditions()) {
        rhsColumns.add(tablePrefix + eq.getRightColumn());
      }
    }

    Map<String, JoinFilterColumnCorrelationAnalysis> correlations = new LinkedHashMap<>();

    for (String rhsColumn : rhsColumns) {
      Set<String> correlatedBaseColumns = new HashSet<>();
      Set<Expr> correlatedBaseExpressions = new HashSet<>();

      getCorrelationForRHSColumn(
          joinableClauses,
          equiConditions,
          rhsColumn,
          correlatedBaseColumns,
          correlatedBaseExpressions
      );

      if (correlatedBaseColumns.isEmpty() && correlatedBaseExpressions.isEmpty()) {
        continue;
      }

      correlations.put(
          rhsColumn,
          new JoinFilterColumnCorrelationAnalysis(
              rhsColumn,
              correlatedBaseColumns,
              correlatedBaseExpressions
          )
      );
    }

    if (correlations.size() == 0) {
      return Optional.empty();
    } else {
      return Optional.of(correlations);
    }
  }

  /**
   * Helper method for {@link #findCorrelatedBaseTableColumns} that determines correlated base table columns
   * and/or expressions for a single RHS column and adds them to the provided sets as it traverses the
   * equicondition column relationships.
   *
   * @param equiConditions            Map of equiconditions, keyed by the right hand columns
   * @param rhsColumn                 RHS column to find base table correlations for
   * @param correlatedBaseColumns     Set of correlated base column names for the provided RHS column. Will be modified.
   * @param correlatedBaseExpressions Set of correlated base column expressions for the provided RHS column. Will be
   *                                  modified.
   */
  private static void getCorrelationForRHSColumn(
      List<JoinableClause> joinableClauses,
      Map<String, Set<Expr>> equiConditions,
      String rhsColumn,
      Set<String> correlatedBaseColumns,
      Set<Expr> correlatedBaseExpressions
  )
  {
    String findMappingFor = rhsColumn;
    Set<Expr> lhsExprs = equiConditions.get(findMappingFor);
    if (lhsExprs == null) {
      return;
    }

    for (Expr lhsExpr : lhsExprs) {
      String identifier = lhsExpr.getBindingIfIdentifier();
      if (identifier == null) {
        // We push down if the function only requires base table columns
        Expr.BindingDetails bindingDetails = lhsExpr.analyzeInputs();
        Set<String> requiredBindings = bindingDetails.getRequiredBindings();

        if (areSomeColumnsFromJoin(joinableClauses, requiredBindings)) {
          break;
        }
        correlatedBaseExpressions.add(lhsExpr);
      } else {
        // simple identifier, see if we can correlate it with a column on the base table
        findMappingFor = identifier;
        if (isColumnFromJoin(joinableClauses, identifier) == null) {
          correlatedBaseColumns.add(findMappingFor);
        } else {
          getCorrelationForRHSColumn(
              joinableClauses,
              equiConditions,
              findMappingFor,
              correlatedBaseColumns,
              correlatedBaseExpressions
          );
        }
      }
    }
  }

  /**
   * Given a list of JoinFilterColumnCorrelationAnalysis, prune the list so that we only have one
   * JoinFilterColumnCorrelationAnalysis for each unique combination of base columns.
   * 
   * Suppose we have a join condition like the following, where A is the base table:
   * A.joinColumn == B.joinColumn && A.joinColumn == B.joinColumn2
   * 
   * We only need to consider one correlation to A.joinColumn since B.joinColumn and B.joinColumn2 must
   * have the same value in any row that matches the join condition.
   * 
   * In the future this method could consider which column correlation should be preserved based on availability of
   * indices and other heuristics.
   * 
   * When push down of filters with LHS expressions in the join condition is supported, this method should also
   * consider expressions.
   *
   * @param originalList Original list of column correlation analyses.
   *
   * @return Pruned list of column correlation analyses.
   */
  private static List<JoinFilterColumnCorrelationAnalysis> eliminateCorrelationDuplicates(
      List<JoinFilterColumnCorrelationAnalysis> originalList
  )
  {
    Map<Set<String>, JoinFilterColumnCorrelationAnalysis> uniquesMap = new HashMap<>();

    for (JoinFilterColumnCorrelationAnalysis jca : originalList) {
      Set<String> mapKey = new HashSet<>(jca.getBaseColumns());
      for (Expr expr : jca.getBaseExpressions()) {
        mapKey.add(expr.stringify());
      }

      uniquesMap.put(mapKey, jca);
    }

    return new ArrayList<>(uniquesMap.values());
  }

  private static boolean filterMatchesNull(Filter filter)
  {
    ValueMatcher valueMatcher = filter.makeMatcher(ALL_NULL_COLUMN_SELECTOR_FACTORY);
    return valueMatcher.matches();
  }

  @Nullable
  private static JoinableClause isColumnFromJoin(
      List<JoinableClause> joinableClauses,
      String column
  )
  {
    for (JoinableClause joinableClause : joinableClauses) {
      if (joinableClause.includesColumn(column)) {
        return joinableClause;
      }
    }

    return null;
  }

  private static boolean isColumnFromPostJoinVirtualColumns(
      List<VirtualColumn> postJoinVirtualColumns,
      String column
  )
  {
    for (VirtualColumn postJoinVirtualColumn : postJoinVirtualColumns) {
      if (column.equals(postJoinVirtualColumn.getOutputName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean areSomeColumnsFromJoin(
      List<JoinableClause> joinableClauses,
      Collection<String> columns
  )
  {
    for (String column : columns) {
      if (isColumnFromJoin(joinableClauses, column) != null) {
        return true;
      }
    }
    return false;
  }

  private static boolean areSomeColumnsFromPostJoinVirtualColumns(
      List<VirtualColumn> postJoinVirtualColumns,
      Collection<String> columns
  )
  {
    for (String column : columns) {
      if (isColumnFromPostJoinVirtualColumns(postJoinVirtualColumns, column)) {
        return true;
      }
    }
    return false;
  }

  private static void splitVirtualColumns(
      List<JoinableClause> joinableClauses,
      final VirtualColumns virtualColumns,
      final List<VirtualColumn> preJoinVirtualColumns,
      final List<VirtualColumn> postJoinVirtualColumns
  )
  {
    for (VirtualColumn virtualColumn : virtualColumns.getVirtualColumns()) {
      if (areSomeColumnsFromJoin(joinableClauses, virtualColumn.requiredColumns())) {
        postJoinVirtualColumns.add(virtualColumn);
      } else {
        preJoinVirtualColumns.add(virtualColumn);
      }
    }
  }

  /**
   * Determine candidates for filter rewrites.
   * A candidate is an RHS column that appears in a filter, along with the value being filtered on, plus
   * the joinable clause associated with the table that the RHS column is from.
   *
   * These candidates are redued to filter rewrite correlations.
   *
   * @param normalizedJoinTableClauses
   * @param equiconditions
   * @param joinableClauses
   * @return A set of candidates for filter rewrites.
   */
  private static Set<RhsRewriteCandidate> getRhsRewriteCandidates(
      List<Filter> normalizedJoinTableClauses,
      Map<String, Set<Expr>> equiconditions,
      List<JoinableClause> joinableClauses)
  {
    Set<RhsRewriteCandidate> rhsRewriteCandidates = new LinkedHashSet<>();
    for (Filter orClause : normalizedJoinTableClauses) {
      if (filterMatchesNull(orClause)) {
        continue;
      }

      if (orClause instanceof OrFilter) {
        for (Filter subFilter : ((OrFilter) orClause).getFilters()) {
          Optional<RhsRewriteCandidate> rhsRewriteCandidate = determineRhsRewriteCandidatesForSingleFilter(
              subFilter,
              equiconditions,
              joinableClauses
          );

          rhsRewriteCandidate.ifPresent(rhsRewriteCandidates::add);
        }
        continue;
      }

      Optional<RhsRewriteCandidate> rhsRewriteCandidate = determineRhsRewriteCandidatesForSingleFilter(
          orClause,
          equiconditions,
          joinableClauses
      );

      rhsRewriteCandidate.ifPresent(rhsRewriteCandidates::add);
    }
    return rhsRewriteCandidates;
  }

  /**
   * A candidate is an RHS column that appears in a filter, along with the value being filtered on, plus
   * the joinable clause associated with the table that the RHS column is from.
   */
  private static class RhsRewriteCandidate
  {
    private final boolean isDirectRewrite;
    private final JoinableClause joinableClause;
    private final String rhsColumn;
    private final String valueForRewrite;

    public RhsRewriteCandidate(
        JoinableClause joinableClause,
        String rhsColumn,
        String valueForRewrite,
        boolean isDirectRewrite
    )
    {
      this.joinableClause = joinableClause;
      this.rhsColumn = rhsColumn;
      this.valueForRewrite = valueForRewrite;
      this.isDirectRewrite = isDirectRewrite;
    }

    public JoinableClause getJoinableClause()
    {
      return joinableClause;
    }

    public String getRhsColumn()
    {
      return rhsColumn;
    }

    public String getValueForRewrite()
    {
      return valueForRewrite;
    }

    /**
     * A direct rewrite occurs when we filter on an RHS column that is also part of a join equicondition.
     *
     * For example, if we have the filter (j.x = 'hello') and the join condition is (y = j.x), we can directly
     * rewrite the j.x filter to (y = 'hello').
     */
    public boolean isDirectRewrite()
    {
      return isDirectRewrite;
    }
  }
}
