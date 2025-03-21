/*******************************************************************************
 * Copyright (c) 2007 - 2025 Maxprograms.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-v10.html
 *
 * Contributors:
 *     Maxprograms - initial API and implementation
 *******************************************************************************/

package com.maxprograms.swordfish;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;

import javax.xml.parsers.ParserConfigurationException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.SAXException;

import com.maxprograms.converters.EncodingResolver;
import com.maxprograms.converters.FileFormats;
import com.maxprograms.languages.Language;
import com.maxprograms.languages.LanguageUtils;
import com.maxprograms.swordfish.models.Memory;
import com.maxprograms.xml.Attribute;
import com.maxprograms.xml.CatalogBuilder;
import com.maxprograms.xml.Document;
import com.maxprograms.xml.Element;
import com.maxprograms.xml.Indenter;
import com.maxprograms.xml.SAXBuilder;
import com.maxprograms.xml.XMLOutputter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class ServicesHandler implements HttpHandler {

	private static Logger logger = System.getLogger(ServicesHandler.class.getName());

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			String request;
			URI uri = exchange.getRequestURI();
			try (InputStream is = exchange.getRequestBody()) {
				request = TmsServer.readRequestBody(is);
			}
			JSONObject response = processRequest(uri.toString(), request);
			byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseHeaders().add("content-type", "application/json; charset=utf-8");
			try (ByteArrayInputStream stream = new ByteArrayInputStream(bytes)) {
				try (OutputStream os = exchange.getResponseBody()) {
					byte[] array = new byte[2048];
					int read;
					while ((read = stream.read(array)) != -1) {
						os.write(array, 0, read);
					}
				}
			}
		} catch (IOException e) {
			MessageFormat mf = new MessageFormat(Messages.getString("ServicesHandler.0"));
			logger.log(Level.ERROR, mf.format(new String[] { exchange.getRequestURI().toString() }), e);
		}
	}

	private JSONObject processRequest(String url, String request) {
		JSONObject result = null;
		try {
			if ("/services/getLanguages".equals(url)) {
				result = getLanguages();
			} else if ("/services/getFileTypes".equals(url)) {
				result = getFileFormats();
			} else if ("/services/getCharsets".equals(url)) {
				result = getCharsets();
			} else if ("/services/getFileType".equals(url)) {
				result = getFileType(request);
			} else if ("/services/getClients".equals(url)) {
				result = getClients();
			} else if ("/services/getSubjects".equals(url)) {
				result = getSubjects();
			} else if ("/services/getProjects".equals(url)) {
				result = getProjectNames();
			} else if ("/services/getSpellingLanguages".equals(url)) {
				result = getSpellingLanguages(request);
			} else if ("/services/remoteDatabases".equals(url)) {
				result = RemoteUtils.remoteDatabases(request);
			} else if ("/services/addDatabases".equals(url)) {
				result = addDatabases(request);
			} else if ("/services/xmlFilters".equals(url)) {
				result = getXmlFilters(request);
			} else if ("/services/importFilter".equals(url)) {
				result = importXmlFilter(request);
			} else if ("/services/removeFilters".equals(url)) {
				result = removeFilters(request);
			} else if ("/services/exportFilters".equals(url)) {
				result = exportFilters(request);
			} else if ("/services/addFilter".equals(url)) {
				result = addFilter(request);
			} else if ("/services/filterData".equals(url)) {
				result = getFilterData(request);
			} else if ("/services/saveElement".equals(url)) {
				result = saveElement(request);
			} else if ("/services/removeElements".equals(url)) {
				result = removeElements(request);
			} else if ("/services/systemInfo".equals(url)) {
				result = getSystemInformation();
			} else {
				result = new JSONObject();
				result.put("url", url);
				result.put("request", request);
				MessageFormat mf = new MessageFormat(Messages.getString("ServicesHandler.1"));
				result.put(Constants.REASON, mf.format(new String[] { url }));
			}
			if (!result.has(Constants.REASON)) {
				result.put(Constants.STATUS, Constants.SUCCESS);
			} else {
				result.put(Constants.STATUS, Constants.ERROR);
			}
		} catch (Exception j) {
			logger.log(Level.ERROR, j.getMessage(), j);
			result = new JSONObject();
			result.put(Constants.STATUS, Constants.ERROR);
			result.put(Constants.REASON, j.getMessage());
		}
		return result;
	}

	private JSONObject getFileFormats() {
		JSONObject result = new JSONObject();
		JSONArray array = new JSONArray();
		String[] formats = FileFormats.getFormats();
		for (int i = 0; i < formats.length; i++) {
			String format = formats[i];
			JSONObject json = new JSONObject();
			json.put("code", FileFormats.getShortName(format));
			json.put("description", format);
			array.put(json);
		}
		result.put("formats", array);
		return result;
	}

	private JSONObject getCharsets() {
		JSONObject result = new JSONObject();
		JSONArray array = new JSONArray();
		TreeMap<String, Charset> charsets = new TreeMap<>(Charset.availableCharsets());
		Set<String> keys = charsets.keySet();
		Iterator<String> it = keys.iterator();
		while (it.hasNext()) {
			String charset = it.next();
			JSONObject json = new JSONObject();
			json.put("code", charset);
			json.put("description", charsets.get(charset).displayName());
			array.put(json);
		}
		result.put("charsets", array);
		return result;
	}

	private JSONObject addDatabases(String request) throws ParseException, IOException {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		String server = json.getString("server");
		String user = json.getString("user");
		String password = json.getString("password");
		String type = json.getString("type");
		JSONArray array = json.getJSONArray("databases");
		SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		for (int i = 0; i < array.length(); i++) {
			JSONObject obj = array.getJSONObject(i);
			Date creationDate = df.parse(obj.getString("creationDate"));
			Memory mem = new Memory(obj.getString("id"), obj.getString("name"), obj.getString("project"),
					obj.getString("subject"), obj.getString("client"), creationDate, Memory.REMOTE, server, user,
					password);
			if ("memory".equals(type)) {
				MemoriesHandler.addMemory(mem);
			} else {
				GlossariesHandler.addGlossary(mem);
			}
		}
		return result;
	}

	private JSONObject getXmlFilters(String request) {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		File appFolder = new File(json.getString("path"));
		File xmlFiltersFolder = new File(appFolder, "xmlfilter");
		JSONArray array = new JSONArray();
		List<String> list = new Vector<>();
		String[] files = xmlFiltersFolder.list();
		for (int i = 0; i < files.length; i++) {
			if (files[i].endsWith(".xml")) {
				list.add(files[i]);
			}
		}
		Collections.sort(list, (o1, o2) -> o1.compareToIgnoreCase(o2));
		for (int i = 0; i < list.size(); i++) {
			array.put(list.get(i));
		}
		result.put("files", array);
		return result;
	}

	private JSONObject importXmlFilter(String request) {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		File appFolder = new File(json.getString("path"));
		File xmlFiltersFolder = new File(appFolder, "xmlfilter");
		File file = new File(json.getString("file"));
		File targetFile = new File(xmlFiltersFolder, file.getName());
		try {
			SAXBuilder builder = new SAXBuilder();
			builder.setEntityResolver(CatalogBuilder.getCatalog(TmsServer.getCatalogFile()));
			Document doc = builder.build(file);
			if (!"ini-file".equals(doc.getRootElement().getName())) {
				throw new IOException(Messages.getString("ServicesHandler.2"));
			}
			Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | SAXException | ParserConfigurationException | URISyntaxException e) {
			logger.log(Level.ERROR, Messages.getString("ServicesHandler.3"), e);
			result.put(Constants.REASON, e.getMessage());
		}
		return result;
	}

	private JSONObject removeFilters(String request) throws IOException {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		File appFolder = new File(json.getString("path"));
		File xmlFiltersFolder = new File(appFolder, "xmlfilter");
		JSONArray files = json.getJSONArray("files");
		for (int i = 0; i < files.length(); i++) {
			File filter = new File(xmlFiltersFolder, files.getString(i));
			Files.delete(filter.toPath());
		}
		return result;
	}

	private JSONObject exportFilters(String request) throws IOException {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		File appFolder = new File(json.getString("path"));
		File xmlFiltersFolder = new File(appFolder, "xmlfilter");
		File targetFolder = new File(json.getString("folder"));
		if (targetFolder.isFile()) {
			targetFolder = targetFolder.getParentFile();
		}
		JSONArray files = json.getJSONArray("files");
		for (int i = 0; i < files.length(); i++) {
			File source = new File(xmlFiltersFolder, files.getString(i));
			File target = new File(targetFolder, files.getString(i));
			Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		return result;
	}

	private JSONObject addFilter(String request) throws IOException {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		File appFolder = new File(json.getString("path"));
		File xmlFiltersFolder = new File(appFolder, "xmlfilter");
		File configFile = new File(xmlFiltersFolder, "config_" + json.getString("root") + ".xml");
		if (configFile.exists()) {
			result.put(Constants.REASON, Messages.getString("ServicesHandler.4"));
		} else {
			Document doc = new Document(null, "ini-file", "-//MAXPROGRAMS//Converters 2.0.0//EN", "configuration.dtd");
			XMLOutputter outputter = new XMLOutputter();
			try (FileOutputStream out = new FileOutputStream(configFile)) {
				outputter.output(doc, out);
			}
		}
		return result;
	}

	private JSONObject getFilterData(String request)
			throws SAXException, IOException, ParserConfigurationException, URISyntaxException {
		JSONObject json = new JSONObject(request);
		File appFolder = new File(json.getString("path"));
		File xmlFiltersFolder = new File(appFolder, "xmlfilter");
		File configFile = new File(xmlFiltersFolder, json.getString("file"));
		SAXBuilder builder = new SAXBuilder();
		builder.setEntityResolver(CatalogBuilder.getCatalog(TmsServer.getCatalogFile()));
		Document doc = builder.build(configFile);
		return toJSON(doc.getRootElement());
	}

	private JSONObject saveElement(String request)
			throws SAXException, IOException, ParserConfigurationException, URISyntaxException {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		File appFolder = new File(json.getString("path"));
		File xmlFiltersFolder = new File(appFolder, "xmlfilter");
		File configFile = new File(xmlFiltersFolder, json.getString("filter"));
		SAXBuilder builder = new SAXBuilder();
		builder.setEntityResolver(CatalogBuilder.getCatalog(TmsServer.getCatalogFile()));
		Document doc = builder.build(configFile);
		Element root = doc.getRootElement();
		List<Element> tags = root.getChildren("tag");
		Iterator<Element> it = tags.iterator();
		boolean found = false;
		while (it.hasNext()) {
			Element tag = it.next();
			if (tag.getText().equals(json.getString("name"))) {
				found = true;
				tag.setAttribute("hard-break", json.getString("type"));
				String attributes = json.getString("attributes");
				if (!attributes.isBlank()) {
					tag.setAttribute("attributes", attributes);
				} else {
					tag.removeAttribute("attributes");
				}
				String keepSpace = json.getString("keepSpace");
				if ("yes".equals(keepSpace)) {
					tag.setAttribute("keep-format", keepSpace);
				} else {
					tag.removeAttribute("keep-format");
				}
				if ("inline".equals(json.getString("type"))) {
					tag.setAttribute("ctype", json.getString("inline"));
				} else {
					tag.removeAttribute("ctype");
				}
			}
		}
		if (!found) {
			Element tag = new Element("tag");
			tag.setText(json.getString("name"));
			tag.setAttribute("hard-break", json.getString("type"));
			String attributes = json.getString("attributes");
			if (!attributes.isBlank()) {
				tag.setAttribute("attributes", attributes);
			}
			String keepSpace = json.getString("keepSpace");
			if ("yes".equals(keepSpace)) {
				tag.setAttribute("keep-format", keepSpace);
			}
			if ("inline".equals(json.getString("type"))) {
				tag.setAttribute("ctype", json.getString("inline"));
			}
			tags.add(tag);
		}
		Collections.sort(tags, (o1, o2) -> o1.getText().compareTo(o2.getText()));
		root.setChildren(tags);
		Indenter.indent(root, 0);
		XMLOutputter outputter = new XMLOutputter();
		outputter.preserveSpace(true);
		try (FileOutputStream out = new FileOutputStream(configFile)) {
			outputter.output(doc, out);
		}
		return result;
	}

	private JSONObject removeElements(String request)
			throws SAXException, IOException, ParserConfigurationException, URISyntaxException {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		Set<String> names = new TreeSet<>();
		JSONArray array = json.getJSONArray("elements");
		for (int i = 0; i < array.length(); i++) {
			names.add(array.getString(i));
		}
		File appFolder = new File(json.getString("path"));
		File xmlFiltersFolder = new File(appFolder, "xmlfilter");
		File configFile = new File(xmlFiltersFolder, json.getString("filter"));
		SAXBuilder builder = new SAXBuilder();
		builder.setEntityResolver(CatalogBuilder.getCatalog(TmsServer.getCatalogFile()));
		Document doc = builder.build(configFile);
		Element root = doc.getRootElement();
		List<Element> tags = root.getChildren("tag");
		List<Element> newList = new Vector<>();
		Iterator<Element> it = tags.iterator();
		while (it.hasNext()) {
			Element tag = it.next();
			if (!names.contains(tag.getText())) {
				newList.add(tag);
			}
		}
		Collections.sort(newList, (o1, o2) -> o1.getText().compareTo(o2.getText()));
		root.setChildren(newList);
		Indenter.indent(root, 0);
		XMLOutputter outputter = new XMLOutputter();
		outputter.preserveSpace(true);
		try (FileOutputStream out = new FileOutputStream(configFile)) {
			outputter.output(doc, out);
		}
		return result;
	}

	private JSONObject getFileType(String request) {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		JSONArray files = json.getJSONArray("files");
		JSONArray detailsArray = new JSONArray();
		for (int i = 0; i < files.length(); i++) {
			String file = files.getString(i);
			String type = "Unknown";
			String encoding = "Unknown";
			String detected = FileFormats.detectFormat(file);
			if (detected != null) {
				type = FileFormats.getShortName(detected);
				if (type != null) {
					Charset charset = EncodingResolver.getEncoding(file, detected);
					if (charset != null) {
						encoding = charset.name();
					}
				}
			}
			if (encoding.equals("Unknown")) {
				try {
					Charset bom = EncodingResolver.getBOM(file);
					if (bom != null) {
						encoding = bom.name();
					}
				} catch (IOException e) {
					// ignore
				}
			}
			JSONObject details = new JSONObject();
			details.put("file", file);
			details.put("type", type);
			details.put("encoding", encoding);
			detailsArray.put(details);
		}
		result.put("files", detailsArray);
		return result;
	}

	private JSONObject getLanguages() {
		JSONObject result = new JSONObject();
		try {
			List<Language> languages = LanguageUtils.getCommonLanguages();
			JSONArray array = new JSONArray();
			for (int i = 0; i < languages.size(); i++) {
				Language lang = languages.get(i);
				JSONObject json = new JSONObject();
				json.put("code", lang.getCode());
				json.put("description", lang.getDescription());
				array.put(json);
			}
			result.put("languages", array);
		} catch (SAXException | IOException | ParserConfigurationException e) {
			logger.log(Level.ERROR, Messages.getString("ServicesHandler.5"), e);
			result.put(Constants.REASON, e.getMessage());
		}
		return result;
	}

	private static JSONObject getClients() throws IOException {
		File clientsFile = new File(TmsServer.getWorkFolder(), "clients.json");
		if (!clientsFile.exists()) {
			JSONObject clients = new JSONObject();
			clients.put("clients", new JSONArray());
			try (FileOutputStream out = new FileOutputStream(clientsFile)) {
				out.write(clients.toString(2).getBytes(StandardCharsets.UTF_8));
			}
			return clients;
		}
		return TmsServer.readJSON(clientsFile);
	}

	private static JSONObject getSubjects() throws IOException {
		File subjectsFile = new File(TmsServer.getWorkFolder(), "subjects.json");
		if (!subjectsFile.exists()) {
			JSONObject subjects = new JSONObject();
			subjects.put("subjects", new JSONArray());
			try (FileOutputStream out = new FileOutputStream(subjectsFile)) {
				out.write(subjects.toString(2).getBytes(StandardCharsets.UTF_8));
			}
			return subjects;
		}
		return TmsServer.readJSON(subjectsFile);
	}

	private static JSONObject getSystemInformation() {
		JSONObject result = new JSONObject();
		MessageFormat mf = new MessageFormat(Messages.getString("ServicesHandler.6"));
		result.put("swordfish", mf.format(new String[] { Constants.VERSION, Constants.BUILD, }));
		result.put("openxliff", mf.format(new String[] { com.maxprograms.converters.Constants.VERSION,
				com.maxprograms.converters.Constants.BUILD }));
		result.put("xmljava",
				mf.format(new String[] { com.maxprograms.xml.Constants.VERSION, com.maxprograms.xml.Constants.BUILD }));
		mf = new MessageFormat(Messages.getString("ServicesHandler.7"));
		result.put("java",
				mf.format(new String[] { System.getProperty("java.version"), System.getProperty("java.vendor") }));
		return result;
	}

	private static JSONObject getProjectNames() throws IOException {
		File projectsFile = new File(TmsServer.getWorkFolder(), "projects.json");
		if (!projectsFile.exists()) {
			JSONObject projects = new JSONObject();
			projects.put("projects", new JSONArray());
			try (FileOutputStream out = new FileOutputStream(projectsFile)) {
				out.write(projects.toString(2).getBytes(StandardCharsets.UTF_8));
			}
			return projects;
		}
		return TmsServer.readJSON(projectsFile);
	}

	public static void addClient(String client) throws IOException {
		if (client == null || client.isEmpty()) {
			return;
		}
		JSONObject clients = getClients();
		JSONArray array = clients.getJSONArray("clients");
		for (int i = 0; i < array.length(); i++) {
			if (client.equals(array.getString(i))) {
				return;
			}
		}
		clients.put("clients", insertString(client, array));
		File clientsFile = new File(TmsServer.getWorkFolder(), "clients.json");
		try (FileOutputStream out = new FileOutputStream(clientsFile)) {
			out.write(clients.toString(2).getBytes(StandardCharsets.UTF_8));
		}
	}

	public static void addSubject(String subject) throws IOException {
		if (subject == null || subject.isEmpty()) {
			return;
		}
		JSONObject subjects = getSubjects();
		JSONArray array = subjects.getJSONArray("subjects");
		for (int i = 0; i < array.length(); i++) {
			if (subject.equals(array.getString(i))) {
				return;
			}
		}
		subjects.put("subjects", insertString(subject, array));
		File subjectsFile = new File(TmsServer.getWorkFolder(), "subjects.json");
		try (FileOutputStream out = new FileOutputStream(subjectsFile)) {
			out.write(subjects.toString(2).getBytes(StandardCharsets.UTF_8));
		}
	}

	public static void addProjectName(String project) throws IOException {
		if (project == null || project.isEmpty()) {
			return;
		}
		JSONObject projects = getProjectNames();
		JSONArray array = projects.getJSONArray("projects");
		for (int i = 0; i < array.length(); i++) {
			if (project.equals(array.getString(i))) {
				return;
			}
		}
		projects.put("projects", insertString(project, array));
		File projectsFile = new File(TmsServer.getWorkFolder(), "projects.json");
		try (FileOutputStream out = new FileOutputStream(projectsFile)) {
			out.write(projects.toString(2).getBytes(StandardCharsets.UTF_8));
		}
	}

	private static JSONArray insertString(String projectName, JSONArray array) {
		JSONArray result = new JSONArray();
		List<String> list = new Vector<>();
		list.add(projectName);
		for (int i = 0; i < array.length(); i++) {
			list.add(array.getString(i));
		}
		Collections.sort(list, (o1, o2) -> o1.toLowerCase().compareTo(o2.toLowerCase()));
		Iterator<String> it = list.iterator();
		while (it.hasNext()) {
			result.put(it.next());
		}
		return result;
	}

	private JSONObject getSpellingLanguages(String request) {
		JSONObject result = new JSONObject();
		try {
			JSONArray array = new JSONArray();
			JSONObject json = new JSONObject(request);
			JSONArray languages = json.getJSONArray("languages");
			for (int i = 0; i < languages.length(); i++) {
				String code = languages.getString(i);
				JSONArray a = new JSONArray();
				a.put(code);
				a.put(LanguageUtils.getLanguage(code));
				array.put(a);
			}
			result.put("languages", array);
		} catch (IOException | SAXException | ParserConfigurationException e) {
			logger.log(Level.ERROR, e);
			result.put(Constants.REASON, e.getMessage());
		}
		return result;
	}

	private JSONObject toJSON(Element e) {
		JSONObject result = new JSONObject();
		result.put("name", e.getName());
		JSONArray attributes = new JSONArray();
		List<Attribute> atts = e.getAttributes();
		for (int i = 0; i < atts.size(); i++) {
			Attribute a = atts.get(i);
			JSONArray o = new JSONArray();
			o.put(a.getName());
			o.put(a.getValue());
			attributes.put(o);
		}
		if (attributes.length() > 0) {
			result.put("attributes", attributes);
		}
		JSONArray array = new JSONArray();
		List<Element> children = e.getChildren();
		for (int i = 0; i < children.size(); i++) {
			array.put(toJSON(children.get(i)));
		}
		if (array.length() > 0) {
			result.put("children", array);
		}
		String text = e.getText().trim();
		if (!text.isBlank()) {
			result.put("content", text);
		}
		return result;
	}
}