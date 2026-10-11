package edu.svec.fams.report;

import edu.svec.fams.reference.ReferenceData;
import org.springframework.stereotype.Component;

/** Connects the printed report's wording of dropdown values to the database-managed reference lists. */
@Component
final class ReportLabelSource {

    ReportLabelSource(ReferenceData reference) {
        ReportLabels.useLookup(reference::reportLabel);
    }
}
