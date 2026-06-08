package edu.upenn.sam3d.domain.model

/**
 * Top-level destination, switched from the header nav. [RUN] is the wizard (Setup → Done); [REPORTS]
 * is the run-history tab. Kept separate from [WizardStep] because Reports is a sibling of the whole
 * wizard, not a step within it — opening it never disturbs where you are in a run.
 */
enum class AppView { RUN, REPORTS }
