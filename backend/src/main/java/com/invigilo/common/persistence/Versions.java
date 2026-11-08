package com.invigilo.common.persistence;

import com.invigilo.common.error.ApiException;
import java.util.Objects;

/**
 * Optimistic locking for edits made from a form: the client sends back the {@code version} it
 * loaded, and the update is refused if the row changed since. (JPA's own {@code @Version} check
 * only protects against races inside the same request, not against stale forms.)
 */
public final class Versions {

    private Versions() {}

    public static void requireUnchanged(BaseEntity entity, Long clientVersion) {
        if (clientVersion == null) {
            throw ApiException.unprocessable("VERSION_REQUIRED", "The version of the record being edited is required");
        }
        if (!Objects.equals(entity.getVersion(), clientVersion)) {
            throw ApiException.conflict("STALE_UPDATE",
                    "This record was changed by someone else. Reload it and apply your changes again.");
        }
    }
}
