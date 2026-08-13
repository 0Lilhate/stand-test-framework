package ru.alfa.stand.test.ui;

import java.util.List;
import ru.alfa.stand.test.core.event.Attachment;

/**
 * What a failing UI step managed to gather on its failure path: the file-backed artefacts (a screenshot)
 * and how many of its sensitive zones were actually masked before the capture.
 *
 * {@link UiInfrastructureFailure}, which place the artefacts into the step's report and the masked-zone
 * count into the step's diagnostics. Both fields are diagnostics data, not the step's outcome: the failing
 * step stays exactly as classified, and an empty evidence is the honest "nothing to report".
 *
 * @param attachments the artefacts the failing step wants attached (screenshot), possibly empty
 * @param maskedZones how many of the failing step's sensitive zones were actually masked before capture
 */
record UiEvidence(List<Attachment> attachments, int maskedZones) {

    static final UiEvidence EMPTY = new UiEvidence(List.of(), 0);

    UiEvidence {
        attachments = (attachments == null) ? List.of() : List.copyOf(attachments);
        if (maskedZones < 0) {
            throw new IllegalArgumentException("maskedZones must not be negative, but was " + maskedZones);
        }
    }
}
