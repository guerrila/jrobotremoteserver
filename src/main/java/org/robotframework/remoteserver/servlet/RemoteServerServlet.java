/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.robotframework.remoteserver.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.server.XmlRpcHandlerMapping;
import org.apache.xmlrpc.webserver.XmlRpcServlet;
import org.apache.xmlrpc.webserver.XmlRpcServletServer;
import org.robotframework.remoteserver.RemoteServer;
import org.robotframework.remoteserver.context.Context;
import org.robotframework.remoteserver.library.DefaultRemoteLibraryFactory;
import org.robotframework.remoteserver.library.RemoteLibrary;
import org.robotframework.remoteserver.library.RemoteLibraryFactory;
import org.robotframework.remoteserver.xmlrpc.ReflectiveHandlerMapping;
import org.robotframework.remoteserver.xmlrpc.TypeFactory;

/**
 * This servlet uses the same instance of a test library to process all requests
 */
public class RemoteServerServlet extends XmlRpcServlet implements Context {
    private static final long serialVersionUID = -7981676271855172976L;
    private static final ThreadLocal<HttpServletRequest> request = new ThreadLocal<HttpServletRequest>();
    private static final ThreadLocal<RemoteLibrary> currLibrary = new ThreadLocal<RemoteLibrary>();
    private RemoteServer remoteServer;
    private Map<String, RemoteLibrary> libraryMap = new ConcurrentHashMap<String, RemoteLibrary>();

    /**
     * Map the given test library to the specified path. Paths must:
     * <ul>
     * <li>start with a /</li>
     * <li>contain only alphanumeric characters or any of these: / - . _ ~</li>
     * <li>not end in a /</li>
     * <li>not contain a repeating sequence of /s</li>
     * </ul>
     * 
     * Example: <code>putLibrary("/myLib", com.example.MyLibrary);</code>
     * 
     * @param clazz
     *            class of the test library
     * @param path
     *            path to map the test library to
     * @return the previous library mapped to the path, or null if there was no
     *         mapping for the path
     */
    public RemoteLibrary putLibrary(String path, Class<?> clazz) {
        checkPath(path);
        RemoteLibraryFactory libraryFactory = createLibraryFactory();
        Object library;
        try {
            library = clazz.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        RemoteLibrary remoteLibrary = libraryFactory.createRemoteLibrary(library);
        return this.libraryMap.put(path, remoteLibrary);
    }

    /**
     * Removes the library mapped to the given path if the mapping exists
     * 
     * @param path
     *            path for the library whose mapping is to be removed
     * @return the previous library associated with the path, or null if there
     *         was no mapping for the path.
     */
    public RemoteLibrary removeLibrary(String path) {
        return libraryMap.remove(path);
    }

    /**
     * Gets a copy of the current library map. Keys in the map are the paths and
     * the values are {@link RemoteLibrary} wrappers of the libraries being
     * served.
     * 
     * @return a copy of the current library map
     */
    public Map<String, RemoteLibrary> getLibraryMap() {
        return new HashMap<String, RemoteLibrary>(libraryMap);
    }

    @Override
    protected XmlRpcServletServer newXmlRpcServer(ServletConfig pConfig) throws XmlRpcException {
        XmlRpcServletServer server = new XmlRpcServletServer();
        server.setTypeFactory(new TypeFactory(this.getXmlRpcServletServer()));
        return server;
    }

    @Override
    protected XmlRpcHandlerMapping newXmlRpcHandlerMapping() throws XmlRpcException {
        ReflectiveHandlerMapping map = new ReflectiveHandlerMapping();
        map.setRequestProcessorFactoryFactory(new RemoteServerRequestProcessorFactoryFactory(this));
        map.addHandler("keywords", ServerMethods.class);
        map.removePrefixes();
        return map;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        request.set(req);
        try {
            super.service(req, resp);
        } finally {
            request.remove();
        }
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        /*
         * when the client is Jython 2.5.x (old xmlrpclib using HTTP/1.0), the
         * server's sockets got stuck in FIN_WAIT_2 for some time, eventually
         * hitting the limit of open sockets on some Windows systems. adding
         * this header gets Jetty to close the socket.
         */
        String path = req.getServletPath() == null ? "" : req.getServletPath();
        if (req.getPathInfo() != null) {
            path += req.getPathInfo();
        }
        path = cleanPath(path);
        if (libraryMap.containsKey(path)) {
            currLibrary.set(libraryMap.get(path));
            if ("HTTP/1.0".equals(req.getProtocol()))
                resp.addHeader("Connection", "close");
            super.doPost(req, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, String.format("No library mapped to %s", path));
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html");
        String body = getPage();
        resp.setContentLength(body.length());
        PrintWriter out = resp.getWriter();
        out.print(body);
    }

    /**
     * The request is shared so that more context, such as the client address,
     * can be obtained
     * 
     * @return {@link HttpServletRequest} object that contains the request the
     *         client has made of the servlet
     */
    public static HttpServletRequest getRequest() {
        return request.get();
    }

    private String getPage() {
        Map<String, RemoteLibrary> map = new TreeMap<String, RemoteLibrary>(getLibraryMap());
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.0 Transitional//EN\">"
                + "<HTML><HEAD><TITLE>jrobotremoteserver</TITLE></HEAD><BODY>" + "<P>jrobotremoteserver serving:</P>"
                + "<TABLE border='1' cellspacing='0' cellpadding='5'><TR><TH>Path</TH><TH>Library</TH></TR>");
        if (map.isEmpty()) {
            sb.append("<TR><TD COLSPAN=\"2\">No libraries mapped</TD></TR>");
        } else {
            for (String path : map.keySet()) {
                sb.append("<TR><TD>");
                sb.append(path.toString());
                sb.append("</TD><TD>");
                sb.append(StringEscapeUtils.escapeHtml(map.get(path).getName()));
                sb.append("</TD></TR>");
            }
        }
        sb.append("</TABLE></BODY></HTML>");
        return sb.toString();
    }

    /**
     * Returns the library to use in the current context. This should only be
     * used while a request is being processed and only on the same thread that
     * is handling the request.
     * 
     * @return the library to use in the current context
     */
    public RemoteLibrary getLibrary() {
        return currLibrary.get();
    }

    protected RemoteLibraryFactory createLibraryFactory() {
        return new DefaultRemoteLibraryFactory();
    }

    public RemoteServer getRemoteServer() {
        return remoteServer;
    }

    public void setRemoteServer(RemoteServer server) {
        remoteServer = server;
    }

    /**
     * Cleans up the path of an incoming request. Repeating /s are reduced to
     * one /. Trailing /s are removed. A <code>null</code> or empty path is
     * converted to /.
     * 
     * @param path
     *            the path the client requested
     * @return cleaned up path
     */
    private static String cleanPath(String path) {
        path = path == null ? "/" : path;
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        path = path.replaceAll("/+", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static void checkPath(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalPathException(String.format("Path [%s] does not start with a /.", path));
        } else if (path.contains("//")) {
            throw new IllegalPathException(String.format("Path [%s] contains repeated forward slashes.", path));
        } else if (!path.equals("/") && path.endsWith("/")) {
            throw new IllegalPathException(String.format("Path [%s] ends with a /.", path));
        } else if (!path.matches("[a-zA-Z0-9-._~/]+")) {
            throw new IllegalPathException(
                    String.format(
                            "Path [%s] contains disallowed characters (must contain only alphanumeric or any of these: -._~/).",
                            path));
        }
    }
}
