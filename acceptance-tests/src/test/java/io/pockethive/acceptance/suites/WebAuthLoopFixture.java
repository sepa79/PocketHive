package io.pockethive.acceptance.suites;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: author isolated WebAuth loop data and exact expected requests for five test customers.
 * Must not: render production templates, route runtime messages or resolve broker resources.
 * Contract: docs/architecture/acceptance-tests.md#five-customer-redis-webauth-loop-acceptance-da-4.
 */
final class WebAuthLoopFixture {
  static final String TEMPLATE = "templates/http/acceptance-webauth.yaml";
  final List<String> records;
  final Map<String, List<String>> requests;
  private final List<String> keys;
  WebAuthLoopFixture(List<String> keys, String nonce) throws Exception {
    if (keys.size() != 7) throw new IllegalArgumentException("Five customer lists and two shared lists required");
    this.keys = List.copyOf(keys);
    var records = new ArrayList<String>();
    var expected = new LinkedHashMap<String, List<String>>();
    var json = new ObjectMapper();
    for (int i = 0; i < 5; i++) {
      String customer = "customer-" + i;
      String account = "000" + (100 + i);
      String amount = Integer.toString(10 + i);
      records.add(json.writeValueAsString(Map.of("Customer", customer, "AccountNumber", account,
          "Amount", amount, "Nonce", nonce, "ReturnList", keys.get(i))));
      var sources = Map.of("RED", keys.get(i), "BAL", keys.get(5), "TOP", keys.get(6));
      expected.put(customer, List.of("RED", "BAL", "TOP", "RED").stream().map(stage ->
          "<request version=\"1.0\" type=\"" + stage + "\" origin=\"" + nonce + "\" sourceList=\""
              + sources.get(stage) + "\"><account no=\"" + customer
              + "\" refer=\"" + account + "\"/><value amt=\"" + amount + "\" currency=\"GBP\"/></request>").toList());
    }
    this.records = List.copyOf(records);
    this.requests = Map.copyOf(expected);
  }
  ObjectNode scenario(JsonNode source, String id) throws Exception {
    var owned = (ObjectNode) new ObjectMapper().readTree(substitute(source.toString()));
    owned.put("id", id);
    owned.put("name", id);
    return owned;
  }
  String substitute(String source) {
    String result = source.replace("REPLACE_RED_KEYS", "(?:" + String.join("|", keys.subList(0, 5)) + ")");
    for (int i = 0; i < keys.size(); i++) result = result.replace("REPLACE_LIST_" + i, keys.get(i));
    return result;
  }
}
