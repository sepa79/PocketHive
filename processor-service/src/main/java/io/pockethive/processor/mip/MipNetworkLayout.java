package io.pockethive.processor.mip;

import java.util.Map;
import java.util.Set;

/**
 * Responsibility: validate the explicit network-management response field layout.
 * Must not: infer scheme codes, parse schemas or own accepted sessions.
 * Contract: RESP-PROCESSOR-MIP-NETWORK — docs/architecture/runtime-responsibilities.md#resp-processor-mip-network.
 */
public record MipNetworkLayout(Set<String> supportedCodes, Set<Integer> copyFields,
                               Map<Integer, String> responseFields) {
  public MipNetworkLayout {
    supportedCodes = Set.copyOf(supportedCodes);
    copyFields = Set.copyOf(copyFields);
    responseFields = Map.copyOf(responseFields);
    if (supportedCodes.isEmpty() || supportedCodes.stream().anyMatch(code -> !code.matches("[0-9]{3}"))) {
      throw new IllegalArgumentException("supportedCodes must declare three-digit network codes");
    }
    if (!copyFields.containsAll(Set.of(MipFields.STAN, MipFields.NETWORK_CODE))) {
      throw new IllegalArgumentException("network layout must copy DE11 and DE70");
    }
    if (!responseFields.containsKey(MipFields.RESPONSE_CODE)) {
      throw new IllegalArgumentException("network layout must set DE39 explicitly");
    }
    for (int number : copyFields) validateNumber(number);
    for (var field : responseFields.entrySet()) {
      int number = field.getKey();
      String value = field.getValue();
      validateNumber(number);
      if (copyFields.contains(number)) throw new IllegalArgumentException("copy/static field overlap: " + number);
      if (value.isBlank()) throw new IllegalArgumentException("blank network response field: " + number);
    }
  }

  private static void validateNumber(int number) {
    if (number < 2 || number > 128) throw new IllegalArgumentException("ISO field number out of range: " + number);
  }
}
