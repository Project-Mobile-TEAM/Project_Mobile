package com.utechat.backend.health;

import com.utechat.backend.controller.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm tra hợp đồng HTTP và bảo đảm lỗi CSDL không làm lộ secret. */
@WebMvcTest(HealthController.class)
class HealthControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private JdbcTemplate jdbc;

    @Test
    void livenessDoesNotTouchDatabase() throws Exception {
        // Liveness phải trả UP kể cả khi không thể dùng JDBC.
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void readinessQueriesDatabase() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        mvc.perform(get("/api/health/ready")).andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("CONNECTED"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        verify(jdbc).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void databaseFailureIsSanitized() throws Exception {
        // Secret giả chỉ nằm trong exception; response không được chứa nó.
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("SECRET_SENTINEL"));
        mvc.perform(get("/api/health/ready")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.database").value("DISCONNECTED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SECRET_SENTINEL"))));
    }

    @Test
    void unexpectedAndNullResultAreNotSuccess() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(2, null);
        // Cả kết quả khác1 lẫn null đều không đủ điều kiện cho readiness.
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/health/ready")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.database").value("UNEXPECTED_RESPONSE"));
        }
    }
}
