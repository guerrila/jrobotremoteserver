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

import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.XmlRpcRequest;
import org.apache.xmlrpc.server.RequestProcessorFactoryFactory;
import org.robotframework.remoteserver.context.Context;

public class RemoteServerRequestProcessorFactoryFactory implements RequestProcessorFactoryFactory {
    private final RequestProcessorFactory factory = new RemoteServerRequestProcessorFactory();
    private final ServerMethods serverMethods;

    public RemoteServerRequestProcessorFactoryFactory(Context context) {
	this.serverMethods = new ServerMethods(context);
    }

    public RequestProcessorFactory getRequestProcessorFactory(@SuppressWarnings("rawtypes") Class aClass)
	    throws XmlRpcException {
	return factory;
    }

    private class RemoteServerRequestProcessorFactory implements RequestProcessorFactory {
	public Object getRequestProcessor(XmlRpcRequest xmlRpcRequest) throws XmlRpcException {
	    return serverMethods;
	}
    }
}