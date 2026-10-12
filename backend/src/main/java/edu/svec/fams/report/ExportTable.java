package edu.svec.fams.report;

import java.util.List;

/**
 * A table to be handed over as a spreadsheet or a CSV file: a title, a header row and rows of cells. A cell is a String,
 * a {@link java.time.LocalDate}, a number or null (empty). The same table gives the Excel workbook and the CSV file, so
 * the two never differ.
 *
 * @param sheetName the worksheet's name (at most 31 characters; forbidden characters are replaced)
 * @param title     the first line of the sheet, for example "Faculty appraisal details - 2025-26 - CSE"
 * @param subtitle  an optional second line (when and by whom it was produced); may be null
 */
public record ExportTable(String sheetName, String title, String subtitle, List<String> headers, List<List<Object>> rows) {}
