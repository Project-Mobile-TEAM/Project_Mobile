package com.utechat.ui.contacts;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Mỗi dòng là một ca kiểm thử riêng để thấy rõ định dạng hoặc biên độ dài bị sai. */
@RunWith(Parameterized.class)
public class ContactsValidatorTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
            {"VN trong nước", "0901111111", "+84901111111"},
            {"VN có dấu cách", "090 111 1111", "+84901111111"},
            {"VN có ngoặc và gạch", "(090) 111-1111", "+84901111111"},
            {"VN đã chuẩn hóa", "+84901111111", "+84901111111"},
            {"VN quốc tế có dấu cách", "+84 901 111 111", "+84901111111"},
            {"Quốc tế", "+14155551234", "+14155551234"},
            {"Quốc tế có định dạng", " +1 (415) 555-1234 ", "+14155551234"},
            {"Biên tối thiểu 8 chữ số", "+12345678", "+12345678"},
            {"Biên tối đa 15 chữ số", "+123456789012345", "+123456789012345"},
            {"Null", null, null},
            {"Rỗng", "", null},
            {"Chỉ dấu cách", "   ", null},
            {"Chỉ ký tự định dạng", "()-", null},
            {"Chỉ dấu cộng", "+", null},
            {"Có chữ cuối", "0901111111abc", null},
            {"Có chữ giữa", "090x1111111", null},
            {"Hai dấu cộng", "++84901111111", null},
            {"Dấu cộng ở giữa", "84+901111111", null},
            {"Mã quốc gia bắt đầu bằng 0", "+0123456789", null},
            {"Thiếu dấu cộng quốc tế", "14155551234", null},
            {"Quốc tế ngắn 7 chữ số", "+1234567", null},
            {"Quốc tế dài 16 chữ số", "+1234567890123456", null},
            {"VN thiếu một chữ số", "090111111", null},
            {"VN thừa một chữ số", "09011111111", null},
            {"VN hai số 0 đầu", "0090111111", null},
            {"Ký tự xuống dòng", "0901111111\n", null},
            {"Ký tự tab", "090\t1111111", null},
            {"Chữ số full-width", "０９０１１１１１１１", null}
        });
    }

    private final String input;
    private final String expected;

    public ContactsValidatorTest(String description, String input, String expected) {
        this.input = input;
        this.expected = expected;
    }

    /** So sánh cả số hợp lệ lẫn null để không bỏ sót trường hợp dữ liệu bị sửa âm thầm. */
    @Test public void normalizesOrRejectsInput() {
        assertEquals(expected, ContactsValidator.normalize(input));
    }
}
