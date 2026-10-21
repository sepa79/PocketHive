package io.pockethive.worker.sdk.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * Responsibility: expose the existing col0-col9 bean aliases for an already projected JSON object.
 * Must not: parse documents, evaluate templates or mutate Work items.
 * Contract: RESP-WORK-TEMPLATE-CONTEXT — docs/architecture/runtime-responsibilities.md#resp-work-template-context.
 * Consumes RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class PayloadWrapper extends HashMap<String, Object> {
    public PayloadWrapper(Map<String, Object> data) {
        super(data);
    }

    public Object getCol0() { return get("col0"); }
    public Object getCol1() { return get("col1"); }
    public Object getCol2() { return get("col2"); }
    public Object getCol3() { return get("col3"); }
    public Object getCol4() { return get("col4"); }
    public Object getCol5() { return get("col5"); }
    public Object getCol6() { return get("col6"); }
    public Object getCol7() { return get("col7"); }
    public Object getCol8() { return get("col8"); }
    public Object getCol9() { return get("col9"); }
}
