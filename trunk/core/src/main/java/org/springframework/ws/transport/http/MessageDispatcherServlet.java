/*
 * Copyright 2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ws.transport.http;

import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FrameworkServlet;
import org.springframework.web.util.WebUtils;
import org.springframework.ws.WebServiceMessageFactory;
import org.springframework.ws.server.EndpointAdapter;
import org.springframework.ws.server.EndpointExceptionResolver;
import org.springframework.ws.server.EndpointMapping;
import org.springframework.ws.server.MessageDispatcher;
import org.springframework.ws.transport.WebServiceMessageReceiver;
import org.springframework.ws.transport.support.DefaultStrategiesHelper;
import org.springframework.ws.wsdl.WsdlDefinition;

/**
 * Servlet for simplified dispatching of Web service messages.
 * <p/>
 * <p>This servlet is a convenient alternative to the standard Spring-MVC {@link DispatcherServlet} with separate {@link
 * WebServiceMessageReceiverHandlerAdapter}, {@link MessageDispatcher}, and {@link WsdlDefinitionHandlerAdapter}
 * instances.
 * <p/>
 * <p>This servlet automatically detects {@link EndpointAdapter EndpointAdapters}, {@link EndpointMapping
 * EndpointMappings}, and {@link EndpointExceptionResolver EndpointExceptionResolvers} by type.
 * <p/>
 * <p>This servlet also automatically detects any {@link WsdlDefinition} in its application context. This WSDL is
 * exposed under the bean name: for example, a {@link WsdlDefinition} bean named '<code>echo</code>' will be exposed as
 * <code>echo.wsdl</code> in this servlet's context: <code>http://localhost:8080/spring-ws/echo.wsdl</code>. When the
 * <code>transformWsdlLocations</code> init-param is set to <code>true</code> in this servlet's configuration in
 * <code>web.xml</code>, all <code>location</code> attributes in the WSDL definitions will reflect the URL of the
 * incoming request.
 *
 * @author Arjen Poutsma
 * @see org.springframework.web.servlet.DispatcherServlet
 * @see org.springframework.ws.server.MessageDispatcher
 * @see org.springframework.ws.transport.http.WebServiceMessageReceiverHandlerAdapter
 */
public class MessageDispatcherServlet extends FrameworkServlet {

    /** Well-known name for the {@link WebServiceMessageFactory} bean in the bean factory for this namespace. */
    public static final String WEB_SERVICE_MESSAGE_FACTORY_BEAN_NAME = "messageFactory";

    /** Well-known name for the {@link WebServiceMessageReceiver} object in the bean factory for this namespace. */
    public static final String MESSAGE_RECEIVER_BEAN_NAME = "messageReceiver";

    /**
     * Name of the class path resource (relative to the {@link MessageDispatcherServlet} class) that defines
     * <code>MessageDispatcherServlet's</code> default strategy names.
     */
    private static final String DEFAULT_STRATEGIES_PATH = "MessageDispatcherServlet.properties";

    /** Suffix of a WSDL request uri. */
    private static final String WSDL_SUFFIX_NAME = ".wsdl";

    private final DefaultStrategiesHelper defaultStrategiesHelper;

    /** The {@link WebServiceMessageReceiverHandlerAdapter} used by this servlet. */
    private WebServiceMessageReceiverHandlerAdapter messageReceiverHandlerAdapter =
            new WebServiceMessageReceiverHandlerAdapter();

    /** The {@link WsdlDefinitionHandlerAdapter} used by this servlet. */
    private WsdlDefinitionHandlerAdapter wsdlDefinitionHandlerAdapter = new WsdlDefinitionHandlerAdapter();

    /** The {@link WebServiceMessageReceiver} used by this servlet. */
    private WebServiceMessageReceiver messageReceiver;

    /** Keys are bean names, values are {@link WsdlDefinition WsdlDefinitions}. */
    private Map wsdlDefinitions;

    private boolean transformWsdlLocations = false;

    /** Public constructor, necessary for some Web application servers. */
    public MessageDispatcherServlet() {
        defaultStrategiesHelper = new DefaultStrategiesHelper(
                new ClassPathResource(DEFAULT_STRATEGIES_PATH, MessageDispatcherServlet.class));
    }

    /**
     * Sets whether relative address locations in the WSDL are to be transformed using the request URI of the incoming
     * {@link HttpServletRequest}. <p>Defaults to <code>false</code>.
     */
    public void setTransformWsdlLocations(boolean transformWsdlLocations) {
        this.transformWsdlLocations = transformWsdlLocations;
    }

    /** Returns the {@link WebServiceMessageReceiver} used by this servlet. */
    protected WebServiceMessageReceiver getMessageReceiver() {
        return messageReceiver;
    }

    protected void initFrameworkServlet() throws ServletException, BeansException {
        initHandlerAdapters();
        initMessageReceiver();
        initWsdlDefinitions();
    }

    protected long getLastModified(HttpServletRequest httpServletRequest) {
        WsdlDefinition definition = getWsdlDefinition(httpServletRequest);
        if (definition != null) {
            return wsdlDefinitionHandlerAdapter.getLastModified(httpServletRequest, definition);
        }
        else {
            return messageReceiverHandlerAdapter.getLastModified(httpServletRequest, messageReceiver);
        }
    }

    protected void doService(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws Exception {
        WsdlDefinition definition = getWsdlDefinition(httpServletRequest);
        if (definition != null) {
            wsdlDefinitionHandlerAdapter.handle(httpServletRequest, httpServletResponse, definition);
        }
        else {
            messageReceiverHandlerAdapter.handle(httpServletRequest, httpServletResponse, messageReceiver);
        }
    }

    /**
     * Determines the {@link WsdlDefinition} for a given request, or <code>null</code> if none is found.
     * <p/>
     * Default implementation checks whether the request method is <code>GET</code>, whether the request uri ends with
     * <code>".wsdl"</code>, and if there is a <code>WsdlDefinition</code> with the same name as the filename in the
     * request uri.
     *
     * @param request the <code>HttpServletRequest</code>
     * @return a definition, or <code>null</code>
     */
    protected WsdlDefinition getWsdlDefinition(HttpServletRequest request) {
        if ("GET".equals(request.getMethod()) && request.getRequestURI().endsWith(WSDL_SUFFIX_NAME)) {
            String fileName = WebUtils.extractFilenameFromUrlPath(request.getRequestURI());
            return (WsdlDefinition) wsdlDefinitions.get(fileName);
        }
        else {
            return null;
        }
    }

    private void initHandlerAdapters() throws BeansException {
        try {
            // setup the receiver adapter
            messageReceiverHandlerAdapter = new WebServiceMessageReceiverHandlerAdapter();
            initWebServiceMessageFactory();
            messageReceiverHandlerAdapter.afterPropertiesSet();
            // setup the wsdl adapter
            wsdlDefinitionHandlerAdapter = new WsdlDefinitionHandlerAdapter();
            wsdlDefinitionHandlerAdapter.setTransformLocations(transformWsdlLocations);
            wsdlDefinitionHandlerAdapter.afterPropertiesSet();
        }
        catch (Exception ex) {
            throw new BeanInitializationException("Could not initialize handler adapters", ex);
        }
    }

    private void initWebServiceMessageFactory() throws Exception {
        WebServiceMessageFactory messageFactory;
        try {
            messageFactory = (WebServiceMessageFactory) getWebApplicationContext()
                    .getBean(WEB_SERVICE_MESSAGE_FACTORY_BEAN_NAME, WebServiceMessageFactory.class);
        }
        catch (NoSuchBeanDefinitionException ignored) {
            messageFactory = (WebServiceMessageFactory) defaultStrategiesHelper
                    .getDefaultStrategy(WebServiceMessageFactory.class, getWebApplicationContext());
            if (logger.isDebugEnabled()) {
                logger.debug("No WebServiceMessageFactory found in servlet '" + getServletName() + "': using default");
            }
        }
        messageReceiverHandlerAdapter.setMessageFactory(messageFactory);
    }

    private void initMessageReceiver() {
        try {
            messageReceiver = (WebServiceMessageReceiver) getWebApplicationContext()
                    .getBean(MESSAGE_RECEIVER_BEAN_NAME, WebServiceMessageReceiver.class);
        }
        catch (NoSuchBeanDefinitionException ex) {
            messageReceiver = (WebServiceMessageReceiver) defaultStrategiesHelper
                    .getDefaultStrategy(WebServiceMessageReceiver.class, getWebApplicationContext());
            if (messageReceiver instanceof BeanNameAware) {
                ((BeanNameAware) messageReceiver).setBeanName(getServletName());
            }
            if (logger.isDebugEnabled()) {
                logger.debug("No MessageDispatcher found in servlet '" + getServletName() + "': using default");
            }
        }
    }

    /** Find all {@link WsdlDefinition WsdlDefinitions} in the ApplicationContext, incuding ancestor contexts. */
    private void initWsdlDefinitions() {
        wsdlDefinitions = BeanFactoryUtils
                .beansOfTypeIncludingAncestors(getWebApplicationContext(), WsdlDefinition.class, true, false);
    }
}
