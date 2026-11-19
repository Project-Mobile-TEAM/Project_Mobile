package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;

import com.utechat.backend.exception.ApiException;
import com.utechat.backend.service.ContactsService;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Kiểm nhánh xác thực body trước JDBC; không thay cho test HTTP/PostgreSQL. */
class ContactInputValidationTest {
    @Test void acceptsCanonicalUuidInEitherCase() {
        UUID id = UUID.fromString("11111111-aaaa-4bbb-8ccc-111111111111");
        assertEquals(id, ContactsService.addresseeId(id.toString()));
        assertEquals(id, ContactsService.addresseeId(id.toString().toUpperCase(Locale.ROOT)));
    }

    @Test void missingMalformedAndShortenedIdsAreBodyValidationErrors() {
        for (String id : new String[]{null, "", " ", "invalid", "1-1-1-1-1", "11111111-aaaa-4bbb-8ccc-111111111111 "}) {
            ApiException error = assertThrows(ApiException.class, () -> ContactsService.addresseeId(id));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
            assertEquals("VALIDATION", error.getCode());
        }
    }
}
