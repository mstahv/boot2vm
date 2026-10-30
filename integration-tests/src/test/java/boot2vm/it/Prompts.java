package boot2vm.it;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The answers for an interactive Deploy run, in the order the prompts appear.
 * Each step waits for its prompt, so a new or reordered question makes the test
 * fail at that prompt instead of typing answers into the wrong ones.
 */
public class Prompts {

    record Step(String expect, String send) {
    }

    private final List<Step> steps = new ArrayList<>();

    /** Answer the prompt that starts with the label. */
    Prompts answer(String label, String value) {
        steps.add(new Step(Pattern.quote(label) + "[^\\n]*: $", value));
        return this;
    }

    /** Check that the prompt suggests the expected default, and take it. */
    Prompts accept(String label, String expectedDefault) {
        steps.add(new Step(Pattern.quote(label) + "[^\\n]*\\[" + Pattern.quote(expectedDefault) + "\\]: $", ""));
        return this;
    }

    /** The steps for Drive.java: a regex, a tab and the answer on each line. */
    String toScript() {
        var script = new StringBuilder();
        for (Step step : steps) {
            script.append(step.expect()).append('\t').append(step.send()).append('\n');
        }
        return script.toString();
    }
}
