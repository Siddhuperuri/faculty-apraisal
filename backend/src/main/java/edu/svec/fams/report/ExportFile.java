package edu.svec.fams.report;

/** A generated file ready to be sent: its name, format and bytes, and how many data rows it holds. */
public record ExportFile(String fileName, ExportFormat format, byte[] bytes, int rows) {}
