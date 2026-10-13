"use client";

import { Card } from "@/components/ui/primitives";
import { useAppraisal } from "./AppraisalProvider";

/**
 * Tells the faculty member how many courses their cadre teaches and what each earns, beside the course list. It is help,
 * not a rule: nobody has to enter more courses than they teach, and marks stop at the workload maximum.
 */
export function CourseLoadHelp() {
  const { appraisal, counts } = useAppraisal();
  const load = appraisal?.courseLoad;
  if (!appraisal || !load) return null;
  const entered = counts["teaching-courses"] ?? 0;
  const enough = entered >= load.perYear;

  return (
    <Card className="p-4 sm:p-5">
      <p className="text-sm" data-course-load>
        <span className="font-semibold">Your course requirement ({appraisal.cadre}):</span> {load.perSemester} course
        {load.perSemester === 1 ? "" : "s"} a semester, {load.perYear} a year. Each course earns {load.marksPerCourse} marks
        towards the workload part, up to {load.workloadMax}.
      </p>
      <p className="mt-1 text-xs text-muted" aria-live="polite">
        {entered === 0
          ? "No courses added yet."
          : enough
            ? `You have added ${entered}: the workload part is at its maximum. Further courses are recorded but earn no more marks.`
            : `You have added ${entered} of ${load.perYear}.`}
      </p>
    </Card>
  );
}
