package io.pockethive.auth.contract;

import java.util.List;

public record UserGrantsReplaceRequestDto(List<AuthGrantDto> grants) {
    public UserGrantsReplaceRequestDto {
        grants = grants == null ? List.of() : List.copyOf(grants);
    }
}
