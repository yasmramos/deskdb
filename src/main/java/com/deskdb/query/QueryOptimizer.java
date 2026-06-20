package com.deskdb.query;

import com.deskdb.core.Filter;
import com.deskdb.core.Table;
import com.deskdb.index.BTree;

import java.util.List;
import java.util.Optional;

/**
 * Optimizador de consultas que decide si usar un índice o hacer un scan completo.
 */
public class QueryOptimizer {

    /**
     * Plan de ejecución generado por el optimizador.
     */
    public static class QueryPlan {
        private final boolean useIndex;
        private final BTree index;
        private final Filter filter;
        private final String columnName;

        public QueryPlan(boolean useIndex, BTree index, Filter filter, String columnName) {
            this.useIndex = useIndex;
            this.index = index;
            this.filter = filter;
            this.columnName = columnName;
        }

        public boolean isUseIndex() {
            return useIndex;
        }

        public Optional<BTree> getIndex() {
            return Optional.ofNullable(index);
        }

        public Filter getFilter() {
            return filter;
        }

        public String getColumnName() {
            return columnName;
        }
    }

    /**
     * Analiza los filtros de una consulta y devuelve el mejor plan de ejecución.
     * Prioriza el uso de índices si están disponibles para la columna filtrada.
     *
     * @param table   La tabla sobre la que se consulta.
     * @param filters Lista de filtros a aplicar.
     * @return El plan de ejecución óptimo.
     */
    public QueryPlan optimize(Table table, List<Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            // Sin filtros, no hay índice útil posible (a menos que sea un scan de índice completo, pero por ahora full scan)
            return new QueryPlan(false, null, null, null);
        }

        // Estrategia simple: usar el primer filtro que tenga un índice disponible
        for (Filter filter : filters) {
            String columnName = filter.getColumn();
            BTree index = table.getIndex(columnName);

            if (index != null) {
                // Verificar si el tipo de filtro es compatible con el índice
                if (isIndexCompatible(filter)) {
                    return new QueryPlan(true, index, filter, columnName);
                }
            }
        }

        // No se encontró ningún índice útil, fallback a full scan
        return new QueryPlan(false, null, filters.get(0), filters.get(0).getColumn());
    }

    /**
     * Determina si un filtro puede beneficiarse de un índice.
     * Por ahora, soportamos igualdad, mayor/menor que, y rangos.
     */
    private boolean isIndexCompatible(Filter filter) {
        switch (filter.getOperator()) {
            case EQ:
            case GT:
            case LT:
            case GTE:
            case LTE:
                return true;
            case NEQ:
                // No es eficiente con B-Tree estándar
                return false;
            default:
                return false;
        }
    }
}
