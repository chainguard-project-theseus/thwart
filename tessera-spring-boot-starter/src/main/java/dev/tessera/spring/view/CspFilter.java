package dev.tessera.spring.view;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.EnumSet;
import java.util.regex.Pattern;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.WebApplicationInitializer;

/**
 * The one place that decides Tessera's default {@code Content-Security-Policy}. A {@link
 * TesseraView} stamps the request with its page's nonce; when the response commits, this filter
 * adds the policy only if the request is stamped and no policy header is set by then. It runs
 * ahead of every other filter, so Spring Security's {@code HeaderWriterFilter} and the
 * application's filters have had their turn, and their policy wins. Boot registers it through the
 * auto-configuration; in a servlet container it registers itself, as a {@link
 * WebApplicationInitializer}, for REQUEST, ASYNC and ERROR dispatches.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CspFilter implements Filter, WebApplicationInitializer {

  static final String HEADER = "Content-Security-Policy";
  private static final String STAMP = CspFilter.class.getName();

  /** A CSP {@code base64-value}: anything else could change the policy it is spliced into. */
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9+/_-]+={0,2}");

  static volatile boolean warned; // package-private for CspFilterTest

  /**
   * Marks the request as a Tessera page with {@code nonce}, which must be base64. Warns once if
   * this filter is not in the request's chain, so the page goes out without the default policy.
   */
  static void stamp(ServletRequest request, String nonce) {
    if (!NONCE.matcher(nonce).matches()) {
      throw new IllegalStateException(
          "The CSP nonce is not base64, so it cannot go in a Content-Security-Policy header");
    }
    if (request.getAttribute(STAMP) == null && !warned) {
      warned = true;
      LogFactory.getLog(CspFilter.class)
          .warn("Tessera: " + CspFilter.class.getName() + " is not in the filter chain, so pages"
              + " get no default Content-Security-Policy. Register it first for REQUEST, ASYNC and"
              + " ERROR dispatches (guide 4.9), or set tessera.csp.enabled=false.");
    }
    request.setAttribute(STAMP, nonce);
  }

  @Override
  public void onStartup(ServletContext servletContext) {
    FilterRegistration.Dynamic registration =
        servletContext.addFilter("tesseraCspFilter", new CspFilter());
    if (registration != null) { // null: already registered (a Boot war registers it itself)
      registration.setAsyncSupported(true);
      registration.addMappingForUrlPatterns(
          EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR),
          false,
          "/*");
    }
  }

  @Override
  public void init(FilterConfig config) {}

  @Override
  public void destroy() {}

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    OnCommit wrapped = new OnCommit(request, (HttpServletResponse) response);
    request.setAttribute(STAMP, ""); // in the chain, not stamped yet
    chain.doFilter(request, wrapped);
    if (!request.isAsyncStarted()) {
      wrapped.commit(); // the end of the request
    }
  }

  /**
   * Calls {@link #commit} before each point where the response can commit: {@code flushBuffer},
   * {@code sendError}, {@code sendRedirect}, a flush or close of its stream or writer, and a
   * write that reaches the content length or fills the buffer.
   */
  private static final class OnCommit extends HttpServletResponseWrapper {
    private final ServletRequest request;
    private boolean done;
    private long length = -1;
    private long written;
    private ServletOutputStream out;
    private PrintWriter writer;

    OnCommit(ServletRequest request, HttpServletResponse response) {
      super(response);
      this.request = request;
    }

    void commit() {
      Object nonce = request.getAttribute(STAMP);
      if (!done && !"".equals(nonce) && nonce instanceof String && !containsHeader(HEADER)) {
        setHeader(HEADER, "script-src 'nonce-" + nonce + "' 'strict-dynamic'; object-src 'none';"
            + " base-uri 'none'");
      }
      done = true;
    }

    /**
     * The same conditions as Spring Security's {@code OnCommittedResponseWrapper}, so this never
     * fires before its writers have run; an empty body is left to the end of the request.
     */
    void count(int n) {
      written += n;
      int buffer = getBufferSize();
      if ((length > 0 && written >= length) || (buffer > 0 && written >= buffer)) {
        commit();
      }
    }

    @Override
    public void setContentLength(int len) {
      length = len;
      super.setContentLength(len);
    }

    @Override
    public void setContentLengthLong(long len) { // Servlet 3.1
      length = len;
      super.setContentLengthLong(len);
    }

    @Override
    public void flushBuffer() throws IOException {
      commit();
      super.flushBuffer();
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
      commit();
      super.sendError(sc, msg);
    }

    @Override
    public void sendError(int sc) throws IOException {
      commit();
      super.sendError(sc);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
      commit();
      super.sendRedirect(location);
    }

    @Override
    public PrintWriter getWriter() throws IOException {
      if (writer == null) {
        writer = new PrintWriter(super.getWriter()) {
          @Override
          public void flush() {
            commit();
            super.flush();
          }

          @Override
          public void close() {
            commit();
            super.close();
          }
        };
      }
      return writer;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
      ServletOutputStream delegate = super.getOutputStream();
      if (out == null) {
        out = new ServletOutputStream() {
          @Override
          public void write(int b) throws IOException {
            count(1);
            delegate.write(b);
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            count(len);
            delegate.write(b, off, len);
          }

          @Override
          public void flush() throws IOException {
            commit();
            delegate.flush();
          }

          @Override
          public void close() throws IOException {
            commit();
            delegate.close();
          }

          @Override
          public boolean isReady() {
            return delegate.isReady();
          }

          @Override
          public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
          }
        };
      }
      return out;
    }
  }
}
