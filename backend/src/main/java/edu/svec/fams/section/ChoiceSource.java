package edu.svec.fams.section;

import java.util.List;
import java.util.Map;

/**
 * Where the values of a dropdown come from: the database-managed reference lists, not code. A form field names its list
 * ({@link FieldSpec#fromList}); what is offered, and accepted, is whatever the list holds when the request arrives.
 */
public interface ChoiceSource {

    /** The codes offered now (switched-off values left out), in the list's order. For a list with parents, each code once. */
    List<String> active(String list);

    /** The codes offered now under one value of the parent list (the branches of a program). */
    List<String> activeUnder(String list, String parent);

    /** For a list with parents: each parent value and the codes offered under it. */
    Map<String, List<String>> activeByParent(String list);

    /** True when the list holds this code, switched on or off: a record that already has it may keep it. */
    boolean known(String list, String code);

    /** Every code of the list, switched on or off, with its label: old records are shown with the label they were given. */
    Map<String, String> labels(String list);

    /** Nothing is offered. Used where a field is not backed by a list. */
    ChoiceSource NONE = new ChoiceSource() {
        @Override public List<String> active(String list) { return List.of(); }
        @Override public List<String> activeUnder(String list, String parent) { return List.of(); }
        @Override public Map<String, List<String>> activeByParent(String list) { return Map.of(); }
        @Override public boolean known(String list, String code) { return false; }
        @Override public Map<String, String> labels(String list) { return Map.of(); }
    };
}
