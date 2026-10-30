package boot2vm.it;

import java.util.ArrayList;
import java.util.List;

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
        steps.add(new Step(escape(label) + "[^\\n]*: $", value));
        return this;
    }

    /** Check that the prompt suggests the expected default, and take it. */
    Prompts accept(String label, String expectedDefault) {
        steps.add(new Step(escape(label) + "[^\\n]*\\[" + escape(expectedDefault) + "\\]: $", ""));
        return this;
    }

    /** Escape regex metacharacters; the steps are matched by Python, which has no \Q...\E. */
    static String escape(String text) {
        return text.replaceAll("[\\\\.^$|?*+()\\[\\]{}]", "\\\\$0");
    }

    String toJson() {
        var json = new StringBuilder("[");
        for (Step step : steps) {
            if (json.length() > 1) json.append(",");
            json.append("{\"expect\":").append(quote(step.expect()))
                    .append(",\"send\":").append(quote(step.send())).append("}");
        }
        return json.append("]").toString();
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
