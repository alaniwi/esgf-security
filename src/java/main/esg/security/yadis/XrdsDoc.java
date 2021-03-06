/*******************************************************************************
 * Copyright (c) 2011 Earth System Grid Federation
 * ALL RIGHTS RESERVED. 
 * U.S. Government sponsorship acknowledged.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 * 
 * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * 
 * Neither the name of the <ORGANIZATION> nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES 
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
/**
 * eXtensible Resource Descriptor class adapted from 
 * org.openid4java.discovery.xrds.XrdsParserImpl
 * 
 * Earth System Grid/CMIP5
 *
 * Date: 09/08/10
 * 
 * Copyright: (C) 2010 Science and Technology Facilities Council
 * 
 * Licence: Apache License 2.0
 * 
 * @author pjkersha
 */
package esg.security.yadis;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ErrorHandler;

import esg.security.yadis.exceptions.XrdsParseException;


public class XrdsDoc {    
    public static final String W3C_XML_SCHEMA = "http://www.w3.org/2001/XMLSchema";
    public static final String JAXP_SCHEMA_LANGUAGE = "http://java.sun.com/xml/jaxp/properties/schemaLanguage";
    public static final String JAXP_SCHEMA_SOURCE = "http://java.sun.com/xml/jaxp/properties/schemaSource";

    public static final String XRDS_SCHEMA = "xrds.xsd";
    public static final String XRD_SCHEMA = "xrd.xsd";
    public static final String XRD_NS = "xri://$xrd*($v*2.0)";
    public static final String XRD_ELEM_XRD = "XRD";
    public static final String XRD_ELEM_TYPE = "Type";
    public static final String XRD_ELEM_URI = "URI";
    public static final String XRD_ELEM_LOCALID = "LocalID";
    public static final String XRD_ELEM_CANONICALID = "CanonicalID";
    public static final String XRD_ATTR_PRIORITY = "priority";
    public static final String OPENID_NS = "http://openid.net/xmlns/1.0";
    public static final String OPENID_ELEM_DELEGATE = "Delegate";
    
    /**
     * Parse string content into XML document
     * 
     * @param input
     * @return
     * @throws XrdsParseException
     */
    protected Document parseXmlInput(String input) throws XrdsParseException
    {
        if (input == null)
            throw new XrdsParseException("No XML message set");
        
        InputStream xrdSchemaStream = this.getClass().getResourceAsStream(XRD_SCHEMA);
        if (xrdSchemaStream == null)
        	throw new XrdsParseException("Can't find XRD schema file \"" +
        			XRD_SCHEMA + "\"");
        
        InputStream xrdsSchemaStream = this.getClass().getResourceAsStream(XRDS_SCHEMA);
        if (xrdsSchemaStream == null)
        	throw new XrdsParseException("Can't find XRDS schema file \"" +
        			XRDS_SCHEMA + "\"");
        
        try
        {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setValidating(true);
            dbf.setAttribute(JAXP_SCHEMA_LANGUAGE, W3C_XML_SCHEMA);
            dbf.setAttribute(JAXP_SCHEMA_SOURCE, new Object[] {
                xrdSchemaStream,
                xrdsSchemaStream,
            });
            DocumentBuilder builder = dbf.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                public void warning(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });

            return builder.parse(new ByteArrayInputStream(input.getBytes()));
        }
        catch (ParserConfigurationException e)
        {
            throw new XrdsParseException("Parser configuration error", e);
        }
        catch (SAXException e)
        {
            throw new XrdsParseException("Error parsing XML document", e);
        }
        catch (IOException e)
        {
            throw new XrdsParseException("Error reading XRDS document", e);
        }
    }

    protected Map<Node, String> extractElementsByParent(String ns, String elem, 
    		Set<Node> parents, Document document)
    {
        Map<Node, String> result = new HashMap<Node, String>();
        NodeList nodes = document.getElementsByTagNameNS(ns, elem);
        Node node;
        for (int i = 0; i < nodes.getLength(); i++) {
            node = nodes.item(i);
            if (node == null || !parents.contains(node.getParentNode())) 
            	continue;

            String localId = node.getFirstChild() != null && 
            	node.getFirstChild().getNodeType() == Node.TEXT_NODE ?
                node.getFirstChild().getNodeValue() : null;

            result.put(node.getParentNode(), localId);
        }
        return result;
    }
    
    /**
     * 
     * @param serviceTypes
     * @param serviceNode
     * @param type
     */
    protected void addServiceType(Map<Node, Set<String>> serviceTypes, 
    		Node serviceNode, String type)
    {
        Set<String> types = serviceTypes.get(serviceNode);
        if (types == null)
        {
            types = new HashSet<String>();
            serviceTypes.put(serviceNode, types);
        }
        types.add(type);
    }

    /**
     * Get the priority value for a given service element
     * @param node
     * @return
     */
    protected int getPriority(Node node)
    {
        if (node.hasAttributes())
        {
            Node priority = node.getAttributes().getNamedItem(XRD_ATTR_PRIORITY);
            if (priority != null)
                return Integer.parseInt(priority.getNodeValue());
            else
                return XrdsServiceElem.LOWEST_PRIORITY;
        }

        return 0;
    }
    
    public List<XrdsServiceElem> parse(String input, Set<String> targetTypes) 
    	throws XrdsParseException
    {
    	// Parse the string input into an XML document
        Document document = parseXmlInput(input);

        // Get the list of XRD elements
        NodeList xrdNodes = document.getElementsByTagNameNS(XRD_NS, XRD_ELEM_XRD);
        Node lastXRD = null;
        int numXrdNodes = xrdNodes.getLength();
        if (numXrdNodes < 1 || 
        	(lastXRD = xrdNodes.item(numXrdNodes - 1)) == null)
            throw new XrdsParseException("No XRD elements found.");

        // Get the canonical ID, if any (needed for XRIs)
        String canonicalId = null;
        Node canonicalIdNode;
        NodeList canonicalIDs = document.getElementsByTagNameNS(XRD_NS, 
        												XRD_ELEM_CANONICALID);
        for (int i = 0; i < canonicalIDs.getLength(); i++) {
            canonicalIdNode = canonicalIDs.item(i);
            if (canonicalIdNode.getParentNode() != lastXRD) continue;
            if (canonicalId != null)
                throw new XrdsParseException("More than one Canonical ID found.");
            canonicalId = canonicalIdNode.getFirstChild() != null && 
            	canonicalIdNode.getFirstChild().getNodeType() == Node.TEXT_NODE ?
                canonicalIdNode.getFirstChild().getNodeValue() : null;
        }

        // Extract the services that match the specified target types
        NodeList types = document.getElementsByTagNameNS(XRD_NS, XRD_ELEM_TYPE);
        HashMap<Node, Set<String>> serviceTypes = new HashMap<Node, Set<String>>();
        Set<Node> selectedServices = new HashSet<Node>();
        
        Node typeNode, serviceNode;
        for (int i = 0; i < types.getLength(); i++) {
            typeNode = types.item(i);
            String type = typeNode != null && 
            	typeNode.getFirstChild() != null && 
            	typeNode.getFirstChild().getNodeType() == Node.TEXT_NODE ?
                typeNode.getFirstChild().getNodeValue() : null;
            if (type == null) continue;

            // The parent service element for this type
            serviceNode = typeNode.getParentNode();
            
            if (targetTypes == null) {
            	// No target types were specified - get all the service types
            	selectedServices.add(serviceNode);
            }
            else if (targetTypes.contains(type))
            	// Get the specified type
                selectedServices.add(serviceNode);
            
            addServiceType(serviceTypes, serviceNode, type);
        }

        // extract local IDs
        Map<Node, String> serviceLocalIDs = extractElementsByParent(XRD_NS, 
        		XRD_ELEM_LOCALID, 
        		selectedServices, 
        		document);
        Map<Node, String> serviceDelegates = extractElementsByParent(OPENID_NS, 
        		OPENID_ELEM_DELEGATE, 
        		selectedServices, 
        		document);

        // build XrdsServiceEndpoints for all URIs in the found services
        List<XrdsServiceElem> result = new ArrayList<XrdsServiceElem>();
        NodeList uris = document.getElementsByTagNameNS(XRD_NS, XRD_ELEM_URI);
        Node uriNode;
        for (int i = 0; i < uris.getLength(); i++) {
            uriNode = uris.item(i);
            if (uriNode == null || 
            	!selectedServices.contains(uriNode.getParentNode())) 
            	continue;

            String uri = uriNode.getFirstChild() != null && 
            	uriNode.getFirstChild().getNodeType() == Node.TEXT_NODE ?
                uriNode.getFirstChild().getNodeValue() : null;

            serviceNode = uriNode.getParentNode();
            Set<String> typeSet = serviceTypes.get(serviceNode);

            String localId = (String) serviceLocalIDs.get(serviceNode);
            String delegate = (String) serviceDelegates.get(serviceNode);

            XrdsServiceElem endpoint = new XrdsServiceElem(uri, 
            		typeSet, getPriority(serviceNode), getPriority(uriNode), 
            		localId, delegate, canonicalId);
            result.add(endpoint);
        }

        Collections.sort(result);
        return result;
    }
    
    // Parse Yadis document extracting the given target types
    public List<XrdsServiceElem> parse(String yadisDocContent) throws 
    	XrdsParseException {
    	return parse(yadisDocContent, null);
    }
}

