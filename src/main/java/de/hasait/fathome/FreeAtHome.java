/*
 * Copyright (C) 2019 by Sebastian Hasait (sebastian at hasait dot de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package de.hasait.fathome;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rocks.xmpp.addr.Jid;
import rocks.xmpp.core.XmppException;
import rocks.xmpp.core.net.client.SocketConnectionConfiguration;
import rocks.xmpp.core.session.XmppClient;
import rocks.xmpp.extensions.rpc.RpcManager;
import rocks.xmpp.extensions.rpc.model.Value;
import rocks.xmpp.util.concurrent.AsyncResult;

import de.hasait.fathome.util.http.AsStringContentHandler;
import de.hasait.fathome.util.http.HttpUtil;

/**
 *
 */
public class FreeAtHome {

	private static final Logger log = LoggerFactory.getLogger(FreeAtHome.class);

	private final Set<AbstractFahPart> parts = new HashSet<>();
	private final Map<String, String> sysap = new TreeMap<>();
	private final Map<String, String> config = new TreeMap<>();
	private final Map<Integer, FahString> stringByNameId = new TreeMap<>();
	private final Map<Integer, FahFunction> functionByFunctionId = new TreeMap<>();
	private final Map<String, FahFloor> floorByUid = new TreeMap<>();
	private final Map<String, FahFloor> floorByName = new TreeMap<>();
	private final Map<String, FahRoom> roomByUid = new TreeMap<>();
	private final Map<String, FahDevice> deviceBySerialNumber = new TreeMap<>();
	private final Map<String, FahChannel> channelByName = new TreeMap<>();

	private XmppClient xmppClient;
	private Jid rpcJid;

	public void connect(FreeAtHomeConfiguration configuration) {
		try {
			String fahUsername = configuration.getUsername();
			String fahPassword = configuration.getPassword();
			String fahSysApHostname = configuration.getHostOrIp();

			String xmppDomain = "busch-jaeger.de";
			rpcJid = Jid.of("mrha@" + xmppDomain + "/rpc");

			CloseableHttpClient httpClient = HttpClients.createDefault();
			Map<String, String> jidStrings = new TreeMap<>();
			try {
				try {
					HttpUtil.httpGet(httpClient, "http://" + fahSysApHostname + "/settings.json",
									 new AsStringContentHandler(contentString -> {
										 JSONObject root = new JSONObject(contentString);
										 JSONArray users = root.getJSONArray("users");
										 for (int i = 0; i < users.length(); i++) {
											 JSONObject user = users.getJSONObject(i);
											 String name = user.getString("name");
											 String jidString = user.getString("jid");
											 jidStrings.put(name, jidString);
										 }
									 })
					);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			} finally {
				HttpClientUtils.closeQuietly(httpClient);
			}

			if (!jidStrings.containsKey(fahUsername)) {
				throw new IllegalArgumentException("Invalid username: " + fahUsername + " not in " + jidStrings.keySet());
			}
			Jid userJid = Jid.of(jidStrings.get(fahUsername));
			String xmppUsername = userJid.getLocal();
			log.info("Login using " + xmppUsername + "...");

			SocketConnectionConfiguration connectionConfiguration = SocketConnectionConfiguration.builder() //
																								 .hostname(fahSysApHostname) //
																								 .port(5222) //
																								 .build();

			xmppClient = XmppClient.create(xmppDomain, connectionConfiguration);
			xmppClient.connect();
			xmppClient.login(xmppUsername, fahPassword);

			loadAll();
		} catch (XmppException e) {
			throw new RuntimeException(e);
		}
	}

	public Collection<FahChannel> getAllChannels() {
		return Collections.unmodifiableCollection(channelByName.values());
	}

	public Collection<FahFloor> getAllFloors() {
		return Collections.unmodifiableCollection(floorByUid.values());
	}

	public FahChannel getChannel(String name) {
		return channelByName.get(name);
	}

	public FahFloor getFloorByName(String name) {
		return floorByName.get(name);
	}

	public FahFunction getFunctionByFunctionId(int functionId) {
		return functionByFunctionId.get(functionId);
	}

	public FahRoom getRoomByUid(String uid) {
		return roomByUid.get(uid);
	}

	public FahString getStringByNameId(int nameId) {
		return stringByNameId.get(nameId);
	}

	void addConfig(String name, String value) {
		config.put(name, value);
	}

	void addPart(AbstractFahPart part) {
		if (parts.contains(part)) {
			return;
		}
		part.setFreeAtHome(this);

		if (part instanceof FahString) {
			FahString fah = (FahString) part;
			stringByNameId.put(fah.getId(), fah);
		}
		if (part instanceof FahFunction) {
			FahFunction fah = (FahFunction) part;
			functionByFunctionId.put(fah.getFunctionId(), fah);
		}
		if (part instanceof FahFloor) {
			FahFloor fah = (FahFloor) part;
			floorByUid.put(fah.getUid(), fah);
			String name = fah.getName();
			if (name != null) {
				floorByName.put(name, fah);
			}
		}
		if (part instanceof FahRoom) {
			FahRoom fah = (FahRoom) part;
			roomByUid.put(fah.getUid(), fah);
		}
		if (part instanceof FahDevice) {
			FahDevice fah = (FahDevice) part;
			deviceBySerialNumber.put(fah.getSerialNumber(), fah);
		}
		if (part instanceof FahChannel) {
			FahChannel fah = (FahChannel) part;
			String name = fah.getName();
			if (name != null) {
				channelByName.put(name, fah);
			}
		}
	}

	void addSysap(String name, String value) {
		sysap.put(name, value);
	}

	void loadAll() {
		Value result = rpcCall("RemoteInterface.getAll", Value.of("de"), Value.of("4"), Value.of("0"), Value.of("0"));
		String projectXml = result.getAsString();
		FahProjectParser.parse(projectXml, this);
	}

	Value rpcCall(String methodName, Value... parameters) {
		try {
			RpcManager rpcManager = xmppClient.getManager(RpcManager.class);
			AsyncResult<Value> asyncResult = rpcManager.call(rpcJid, methodName, parameters);
			return asyncResult.getResult();
		} catch (XmppException e) {
			throw new RuntimeException(e);
		}
	}

}
