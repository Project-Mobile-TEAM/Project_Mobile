package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.exception.GlobalExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Kiểm luồng Spring MVC thật với controller fixture, không thêm endpoint vào ứng dụng. */
class MissingPhoneParameterTest {
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new RequiredParameterController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    /** Spring phát hiện thiếu phone trước khi gọi controller và phải trả lỗi nhập liệu. */
    @Test void missingPhoneReturns400PhoneInvalid() throws Exception {
        var response = mvc.perform(get("/test/lookup")).andReturn().getResponse();
        assertEquals(400, response.getStatus());
        JsonNode body = json.readTree(response.getContentAsString());
        assertEquals("PHONE_INVALID", body.path("error").path("code").asText());
        assertFalse(body.has("trace"));
    }

    /** Tham số bắt buộc khác không bị gán nhầm mã lỗi của số điện thoại. */
    @Test void otherMissingParameterReturns400Validation() throws Exception {
        var response = mvc.perform(get("/test/other")).andReturn().getResponse();
        assertEquals(400, response.getStatus());
        assertEquals("VALIDATION", json.readTree(response.getContentAsString()).path("error").path("code").asText());
    }

    /** Request có phone được chuyển nguyên vẹn tới controller. */
    @Test void providedPhoneStillReachesController() throws Exception {
        var response = mvc.perform(get("/test/lookup").param("phone", "+84901111111")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("+84901111111", json.readTree(response.getContentAsString()).path("phone").asText());
    }

    /** Handler mới không làm mất mã 429 và details của lỗi nghiệp vụ hiện có. */
    @Test void existingApiExceptionKeepsStatusCodeAndDetails() throws Exception {
        var response = mvc.perform(get("/test/limited")).andReturn().getResponse();
        assertEquals(429, response.getStatus());
        JsonNode error = json.readTree(response.getContentAsString()).path("error");
        assertEquals("RATE_LIMITED", error.path("code").asText());
        assertEquals(12, error.path("details").path("retryAfterSeconds").asInt());
    }

    /** Chỉ tồn tại trong test để Spring tự tạo MissingServletRequestParameterException. */
    @RestController
    static class RequiredParameterController {
        @GetMapping("/test/lookup")
        public Map<String, String> lookup(@RequestParam("phone") String phone) {
            return Map.of("phone", phone);
        }

        @GetMapping("/test/other")
        public Map<String, String> other(@RequestParam("name") String name) {
            return Map.of("name", name);
        }

        @GetMapping("/test/limited")
        public void limited() {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Wait",
                    Map.of("retryAfterSeconds", 12));
        }
    }
}
