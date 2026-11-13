package hudson.init.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.util.RunListInterruptedException;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InstallUncaughtExceptionHandlerTest {

    @Test
    void interruptedRequest() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse rsp = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(rsp.getWriter()).thenReturn(new PrintWriter(body));
        try {
            Thread.currentThread().interrupt();
            InstallUncaughtExceptionHandler.handleException(null, new ServletException(new RunListInterruptedException()), req, rsp, 500);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        verify(rsp).reset();
        verify(rsp).setStatus(503);
        verify(rsp).setContentType("text/plain;charset=UTF-8");
        assertEquals("Resource-intensive request interrupted before a complete result could be produced.", body.toString());
    }

    @Test
    void jsonResponseResetsOutputModeAndPreservesSecurityHeaders() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse rsp = mock(HttpServletResponse.class);
        when(req.getRequestURI()).thenReturn("/job/example/api/json");
        when(rsp.getHeaderNames()).thenReturn(Set.of("X-Frame-Options", "Content-Length"));
        when(rsp.getHeaders("X-Frame-Options")).thenReturn(List.of("deny"));
        when(rsp.getHeaders("Content-Length")).thenReturn(List.of("123"));
        AtomicReference<String> outputMode = new AtomicReference<>();
        StringWriter body = new StringWriter();

        when(rsp.getOutputStream()).thenAnswer(invocation -> {
            if (!outputMode.compareAndSet(null, "stream")) {
                throw new IllegalStateException("Writer already selected");
            }
            return new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}

                @Override
                public void write(int value) {}
            };
        });
        when(rsp.getWriter()).thenAnswer(invocation -> {
            if (!outputMode.compareAndSet(null, "writer")) {
                throw new IllegalStateException("Output stream already selected");
            }
            return new PrintWriter(body);
        });
        doAnswer(invocation -> {
            outputMode.set(null);
            return null;
        }).when(rsp).reset();

        // Simulate a partially rendered API response that selected the output stream first.
        rsp.getOutputStream();
        InstallUncaughtExceptionHandler.handleException(null, new RunListInterruptedException(), req, rsp, 500);

        verify(rsp).reset();
        verify(rsp).setHeader("X-Frame-Options", "deny");
        verify(rsp, never()).setHeader("Content-Length", "123");
        verify(rsp).setStatus(503);
        verify(rsp).setContentType("application/json;charset=UTF-8");
        assertEquals("{\"status\":\"error\",\"message\":\"Resource-intensive request interrupted before a complete result could be produced.\"}", body.toString());
    }

    @Test
    void committedResponseIsLeftAlone() throws Exception {
        HttpServletResponse rsp = mock(HttpServletResponse.class);
        when(rsp.isCommitted()).thenReturn(true);
        InstallUncaughtExceptionHandler.handleException(null, new RunListInterruptedException(), mock(HttpServletRequest.class), rsp, 500);
        verify(rsp, never()).reset();
        verify(rsp, never()).setStatus(503);
        verify(rsp, never()).getWriter();
    }

    @Test
    void headResponseHasNoBody() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse rsp = mock(HttpServletResponse.class);
        when(req.getMethod()).thenReturn("HEAD");
        InstallUncaughtExceptionHandler.handleException(null, new RunListInterruptedException(), req, rsp, 500);
        verify(rsp).setStatus(503);
        verify(rsp, never()).getWriter();
    }

    @Test
    void unrelatedCancellationIsNotAnInterruptedScan() throws Exception {
        HttpServletResponse rsp = mock(HttpServletResponse.class);
        when(rsp.isCommitted()).thenReturn(true);
        InstallUncaughtExceptionHandler.handleException(null, new CancellationException(), mock(HttpServletRequest.class), rsp, 500);
        verify(rsp, never()).setStatus(503);
    }
}
