package com.utechat.data.local;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Kiểm tra kiểu dữ liệu và chuẩn hóa tiếng Việt mà không cần thiết bị Android. */
public class RoomConvertersTest {
    @Test public void datesAndUuidRoundTrip() {
        Date date = new Date(1731300000123L);
        assertEquals(date, Converters.longToDate(Converters.dateToLong(date)));
        UUID id = UUID.randomUUID();
        assertEquals(id, Converters.stringToUuid(Converters.uuidToString(id)));
        assertNull(Converters.dateToLong(null));
        assertNull(Converters.longToDate(null));
        assertNull(Converters.uuidToString(null));
        assertNull(Converters.stringToUuid(null));
    }

    @Test public void listPreservesVietnameseAndPunctuation() {
        // JSON không làm hỏng dấu phẩy, dấu nháy hoặc ký tự tiếng Việt trong phần tử.
        java.util.List<String> list = Arrays.asList("Chào bạn", "a,b", "\"Đặng\"");
        assertEquals(list, Converters.stringToList(Converters.listToString(list)));
        assertNull(Converters.listToString(null));
        assertNull(Converters.stringToList(null));
    }

    @Test public void ftsNormalizesBothFormsAndQuotesOperators() {
        assertEquals("chao ban dang", MessageText.normalize("Chào BẠN Đặng"));
        assertEquals("chao ban", MessageText.normalize("Cha\u0300o ba\u0323n"));
        assertEquals("\"chao\"* \"ban\"*", MessageText.matchQuery("Chào bạn"));
        assertEquals("\"\"\"\"* \"or\"*", MessageText.matchQuery("\" OR"));
    }
}
