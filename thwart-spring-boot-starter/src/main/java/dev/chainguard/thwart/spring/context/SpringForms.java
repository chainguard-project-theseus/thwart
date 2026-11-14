package dev.chainguard.thwart.spring.context;

import dev.chainguard.thwart.runtime.internal.Escapers;
import dev.chainguard.thwart.runtime.Forms;
import jakarta.servlet.http.HttpServletRequest;
import java.beans.PropertyEditor;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.support.BindStatus;
import org.springframework.web.servlet.support.RequestContext;

/**
 * {@link Forms} over the model's {@link BindingResult}s. Values and errors come from Spring's
 * {@link BindStatus} (not HTML-escaped: Thwart escapes for the output context). Objects
 * without a {@code BindingResult} in the model use the current value and have no errors.
 */
final class SpringForms implements Forms {
  static final String CSRF_ATTRIBUTE = "_csrf";

  private static final boolean SECURITY_PRESENT =
      ClassUtils.isPresent(
          "org.springframework.security.web.csrf.CsrfToken", SpringForms.class.getClassLoader());

  private final RequestContext requestContext;
  private final Map<String, ?> model;
  private final HttpServletRequest request;

  SpringForms(RequestContext requestContext, Map<String, ?> model, HttpServletRequest request) {
    this.requestContext = requestContext;
    this.model = model;
    this.request = request;
  }

  @Override
  public String value(String objectName, String path, Object currentValue) {
    if (!hasBindingResult(objectName)) {
      return Escapers.toText(currentValue);
    }
    return bindStatus(objectName + "." + path).getDisplayValue();
  }

  @Override
  public String selectedValue(String objectName, String path, Object currentValue) {
    if (!hasBindingResult(objectName)) {
      return currentValue == null ? null : Escapers.toText(currentValue);
    }
    BindStatus status = bindStatus(objectName + "." + path);
    return status.getValue() == null ? null : status.getDisplayValue();
  }

  /**
   * Thymeleaf's {@code SpringSelectedValueComparator.isSelected} (a copy of Spring's
   * package-private {@code SelectedValueComparator}) over the field's {@link BindStatus}: the bound
   * (formatted or rejected) value or the raw value equals {@code candidate}, or the raw value is a
   * Collection, array or Map with an element (key) that matches, or the value itself matches, by
   * display text, enum name, or through the field's property editor.
   */
  @Override
  public boolean selected(String objectName, String path, Object currentValue, String candidate) {
    if (!hasBindingResult(objectName)) {
      return Forms.super.selected(objectName, path, currentValue, candidate);
    }
    BindStatus status = bindStatus(objectName + "." + path);
    Object bound = status.getValue();
    if (candidate.equals(bound)) {
      return true;
    }
    Object actual = status.getActualValue();
    if (actual != null && actual != bound && candidate.equals(actual)) {
      return true;
    }
    if (actual != null) {
      bound = actual;
    } else if (bound == null) {
      return false;
    }
    boolean selected = false;
    if (bound.getClass().isArray()) {
      selected = collectionMatches(CollectionUtils.arrayToList(bound), candidate, status);
    } else if (bound instanceof Collection) {
      selected = collectionMatches((Collection<?>) bound, candidate, status);
    } else if (bound instanceof Map) {
      selected = collectionMatches(((Map<?, ?>) bound).keySet(), candidate, status);
    }
    return selected || matches(bound, candidate, status.getEditor(), null);
  }

  private static boolean collectionMatches(Collection<?> collection, String candidate, BindStatus status) {
    try {
      if (collection.contains(candidate)) {
        return true;
      }
    } catch (ClassCastException ex) {
      // A collection that cannot hold Strings: compare element by element.
    }
    Map<PropertyEditor, Object> converted = new HashMap<>(1);
    PropertyEditor editor = null;
    for (Object element : collection) {
      if (editor == null && element != null) {
        editor = status.findEditor(element.getClass());
      }
      if (matches(element, candidate, editor, converted)) {
        return true;
      }
    }
    return false;
  }

  /** {@code SpringSelectedValueComparator.exhaustiveCompare} for a String candidate. */
  private static boolean matches(
      Object bound, String candidate, PropertyEditor editor, Map<PropertyEditor, Object> converted) {
    if (bound != null && bound.getClass().isEnum()) {
      Enum<?> e = (Enum<?>) bound;
      return e.name().equals(candidate) || e.toString().equals(candidate);
    }
    if (ObjectUtils.getDisplayString(bound).equals(candidate)) {
      return true;
    }
    if (editor == null) {
      return false;
    }
    Object value;
    if (converted != null && converted.containsKey(editor)) {
      value = converted.get(editor);
    } else {
      editor.setAsText(candidate);
      value = editor.getValue();
      if (converted != null) {
        converted.put(editor, value);
      }
    }
    return ObjectUtils.nullSafeEquals(bound, value);
  }

  @Override
  public List<String> errors(String objectName, String path) {
    if (!hasBindingResult(objectName)) {
      return Collections.emptyList();
    }
    // "global": the BindStatus of the object name alone holds its global errors (Thymeleaf's
    // #fields.globalErrors(), th:errors="*{global}").
    String bindPath = path.equals("global") ? objectName : objectName + "." + path;
    return Arrays.asList(bindStatus(bindPath).getErrorMessages());
  }

  /**
   * The {@code _csrf} request attribute, read lazily so that a deferred token is only loaded
   * (and a session only created) when a template actually renders a POST form.
   */
  @Override
  public Optional<CsrfToken> csrfToken() {
    return SECURITY_PRESENT ? Security.csrfToken(request) : Optional.empty();
  }

  private boolean hasBindingResult(String objectName) {
    return model.get(BindingResult.MODEL_KEY_PREFIX + objectName) instanceof BindingResult;
  }

  private BindStatus bindStatus(String path) {
    return new BindStatus(requestContext, path, false);
  }

  /** Holds the Spring Security references so they load only when Security is on the classpath. */
  private static final class Security {
    static Optional<CsrfToken> csrfToken(HttpServletRequest request) {
      Object attribute = request.getAttribute(CSRF_ATTRIBUTE);
      if (attribute instanceof org.springframework.security.web.csrf.CsrfToken) {
        org.springframework.security.web.csrf.CsrfToken token =
            (org.springframework.security.web.csrf.CsrfToken) attribute;
        if (token.getToken() != null) {
          return Optional.of(new CsrfToken(token.getParameterName(), token.getToken()));
        }
      }
      return Optional.empty();
    }
  }
}
