package com.invigilo.report;

import java.util.List;

/**
 * A downloadable report, independent of its file format: a title, a few lines of context (college,
 * period, filters) and one or more sections, each a table. {@code groupLabel} names what the section
 * headings are (e.g. "Date") so a flat CSV can carry them as a first column.
 */
public record Report(String title, List<String> details, String groupLabel, List<Section> sections) {

    /** One column; numeric columns become numbers in Excel. */
    public record Column(String name, boolean numeric) {
        public static Column text(String name) {
            return new Column(name, false);
        }

        public static Column number(String name) {
            return new Column(name, true);
        }
    }

    /** A table under an optional heading. Every row has one value per column (empty string for none). */
    public record Section(String heading, List<Column> columns, List<List<String>> rows) {}

    /** True when every section has the same columns, so the report can be one flat table. */
    boolean uniform() {
        return sections.stream().map(s -> s.columns().stream().map(Column::name).toList()).distinct().count() <= 1;
    }
}
