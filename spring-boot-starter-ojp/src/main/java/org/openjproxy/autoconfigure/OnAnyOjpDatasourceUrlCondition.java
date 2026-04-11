package org.openjproxy.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link Condition} that matches when at least one Spring datasource URL starts with
 * {@code jdbc:ojp}.
 *
 * <p>Both the default datasource URL ({@code spring.datasource.url}) and named datasource
 * URLs ({@code spring.datasource.{name}.url}) are considered, allowing the
 * {@link OjpAutoConfiguration} to activate for multi-datasource setups such as:</p>
 * <pre>
 * spring.datasource.catalog.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/catalog
 * spring.datasource.checkout.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/checkout
 * </pre>
 */
class OnAnyOjpDatasourceUrlCondition implements Condition {

    private static final String OJP_URL_PREFIX = "jdbc:ojp";
    private static final String DATASOURCE_URL_PROPERTY = "spring.datasource.url";
    private static final String DATASOURCE_PROP_PREFIX = "spring.datasource.";
    private static final String URL_SUFFIX = ".url";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();

        String url = environment.getProperty(DATASOURCE_URL_PROPERTY);
        if (url != null && url.startsWith(OJP_URL_PREFIX)) {
            return true;
        }

        if (environment instanceof ConfigurableEnvironment configurableEnvironment) {
            for (PropertySource<?> source : configurableEnvironment.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String name : enumerable.getPropertyNames()) {
                        if (isNamedDatasourceUrlProperty(name)) {
                            String namedUrl = environment.getProperty(name);
                            if (namedUrl != null && namedUrl.startsWith(OJP_URL_PREFIX)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private boolean isNamedDatasourceUrlProperty(String name) {
        return name.startsWith(DATASOURCE_PROP_PREFIX)
                && name.endsWith(URL_SUFFIX)
                && !name.equals(DATASOURCE_URL_PROPERTY);
    }
}
