package hudson.init.impl;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.init.Initializer;
import hudson.util.RunListInterruptedException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.MetaInfServices;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.UncaughtExceptionFilter;
import org.kohsuke.stapler.WebApp;

/**
 * Deals with exceptions that get thrown all the way up to the Stapler rendering layer.
 */
public class InstallUncaughtExceptionHandler {

    private static final Logger LOGGER = Logger.getLogger(InstallUncaughtExceptionHandler.class.getName());

    @Initializer
    public static void init(final Jenkins j) throws IOException {
        UncaughtExceptionFilter.setUncaughtExceptionHandler(j.getServletContext(), (e, context, req, rsp) -> handleException(j, e, req, rsp, 500));
        try {
            Thread.setDefaultUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler());
            LOGGER.log(Level.FINE, "Successfully installed a global UncaughtExceptionHandler.");
        }
        catch (SecurityException ex) {
            LOGGER.log(Level.SEVERE,
                                                       "Failed to set the default UncaughtExceptionHandler. " +
                                                       "If any threads die due to unhandled coding errors then there will be no logging of this information. " +
                                                       "The lack of this diagnostic information will make it harder to track down issues which will reduce the supportability of Jenkins. " +
                                                       "It is highly recommended that you consult the documentation that comes with your servlet container on how to allow the " +
                                                       "`setDefaultUncaughtExceptionHandler` permission and enable it.", ex);
        }
    }

    static void handleException(Jenkins j, Throwable e, HttpServletRequest req, HttpServletResponse rsp, int code) throws IOException, ServletException {
        if (isInterruptedRunList(e)) {
            handleInterruptedRequest(e, req, rsp);
            return;
        }
        if (rsp.isCommitted()) {
            LOGGER.log(isEOFException(e) ? Level.FINE : Level.WARNING, null, e);
            return;
        }
        String id = UUID.randomUUID().toString();
        LOGGER.log(isEOFException(e) ? Level.FINE : Level.WARNING, "Caught unhandled exception with ID " + id, e);
        req.setAttribute("jenkins.exception.id", id);
        req.setAttribute("jakarta.servlet.error.exception", e);
        rsp.setStatus(code);
        try {
            WebApp.get(j.getServletContext()).getSomeStapler().invoke(req, rsp, j, "/oops");
        } catch (ServletException | IOException x) {
            if (!Stapler.isSocketException(x)) {
                throw x;
            }
        }
    }

    private static boolean isInterruptedRunList(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RunListInterruptedException) {
                return true;
            }
        }
        return false;
    }

    private static void handleInterruptedRequest(Throwable e, HttpServletRequest req, HttpServletResponse rsp) throws IOException {
        LOGGER.log(Level.FINE, "Build history request interrupted", e);
        if (rsp.isCommitted()) {
            return;
        }

        // Preserve headers installed by filters (for example, security headers), but discard
        // the incomplete response body and any length calculated for it.
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : rsp.getHeaderNames()) {
            if (!"Content-Length".equalsIgnoreCase(name)) {
                headers.put(name, new ArrayList<>(rsp.getHeaders(name)));
            }
        }
        // reset(), unlike resetBuffer(), also clears the writer/output-stream selection.
        rsp.reset();
        headers.forEach((name, values) -> {
            if (!values.isEmpty()) {
                rsp.setHeader(name, values.get(0));
                for (int i = 1; i < values.size(); i++) {
                    rsp.addHeader(name, values.get(i));
                }
            }
        });

        rsp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        boolean jsonApi = req.getRequestURI() != null && req.getRequestURI().endsWith("/api/json");
        rsp.setContentType(jsonApi ? "application/json;charset=UTF-8" : "text/plain;charset=UTF-8");
        if (!"HEAD".equals(req.getMethod())) {
            String message = "Resource-intensive request interrupted before a complete result could be produced.";
            if (jsonApi) {
                rsp.getWriter().write("{\"status\":\"error\",\"message\":\"" + message + "\"}");
            } else {
                rsp.getWriter().write(message);
            }
        }
    }

    @Restricted(NoExternalUse.class)
    @MetaInfServices
    public static class ErrorCustomizer implements HttpResponses.ErrorCustomizer {
        @CheckForNull
        @Override
        public HttpResponses.HttpResponseException handleError(int code, Throwable cause) {
            if (Jenkins.getInstanceOrNull() == null) {
                return null;
            }
            return new HttpResponses.HttpResponseException(cause) {
                @Override
                public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node) throws IOException, ServletException {
                    handleException(Jenkins.get(), cause, req, rsp, code);
                }
            };
        }
    }

    private static boolean isEOFException(Throwable e) {
        if (e == null) {
            return false;
        } else if (e instanceof EOFException) {
            return true;
        } else {
            return isEOFException(e.getCause());
        }
    }

    /** An UncaughtExceptionHandler that just logs the exception */
    private static class DefaultUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

        @Override
        public void uncaughtException(Thread t, Throwable ex) {
            // if this was an OutOfMemoryError then all bets about logging are off - but in the absence of anything else...
            LOGGER.log(Level.SEVERE,
                       "A thread (" + t.getName() + '/' + t.getId()
                                     + ") died unexpectedly due to an uncaught exception. This may leave your server corrupted and usually indicates a software bug.",
                       ex);
        }

    }

    private InstallUncaughtExceptionHandler() {}
}
