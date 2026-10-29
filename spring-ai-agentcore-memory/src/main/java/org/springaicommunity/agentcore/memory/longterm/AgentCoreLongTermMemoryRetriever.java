/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.agentcore.memory.longterm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.memory.AgentCoreMemoryException;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.ListMemoryRecordsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ListMemoryRecordsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.MemoryRecordSummary;
import software.amazon.awssdk.services.bedrockagentcore.model.RetrieveMemoryRecordsRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.RetrieveMemoryRecordsResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.SearchCriteria;

/**
 * Retriever for long-term memories from AgentCore Memory. Supports semantic search
 * (facts, summaries, episodes) and listing (preferences).
 *
 * <p>
 * AgentCore Memory matches the request namespace as a plain string prefix, so a request
 * for actor {@code alice} also returns records stored under actor {@code alice2}. Results
 * are therefore filtered client-side to records whose namespace equals the requested
 * namespace or lies beneath it at a {@code /} boundary.
 *
 * <p>
 * AgentCore Memory stores namespaces as written in the strategy's template, with or
 * without a trailing {@code /}. When startup validation saw the memory namespace that a
 * pattern matches, requests for that pattern follow its trailing slash. For a memory
 * whose namespaces end with {@code /}, the prefix match then stops at a segment boundary,
 * and the records of other actors do not use up {@code topK} before the filter drops
 * them.
 *
 * @author Yuriy Bezsonov
 */
public class AgentCoreLongTermMemoryRetriever {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreLongTermMemoryRetriever.class);

	/** Largest {@code maxResults} that {@code ListMemoryRecords} accepts (default 20). */
	private static final int SERVICE_MAX_RESULTS = 100;

	/**
	 * Maximum number of {@code ListMemoryRecords} pages read per listing. Caps the round
	 * trips and the number of records that end up in the prompt: every listed preference
	 * is sent with every request, so 200 records already cost thousands of tokens.
	 */
	static final int MAX_LIST_PAGES = 2;

	private final BedrockAgentCoreClient client;

	private final String memoryId;

	private final Map<NamespaceKey, Boolean> trailingSlashes;

	public AgentCoreLongTermMemoryRetriever(BedrockAgentCoreClient client, String memoryId) {
		this(client, memoryId, Map.of());
	}

	/**
	 * Creates a retriever whose request namespaces follow the trailing slash of the
	 * memory namespaces that startup validation matched.
	 * @param client the AgentCore client
	 * @param memoryId the memory resource ID
	 * @param trailingSlashes for each validated strategy ID and pattern, whether the
	 * matching memory namespace ends with {@code /}
	 */
	AgentCoreLongTermMemoryRetriever(BedrockAgentCoreClient client, String memoryId,
			Map<NamespaceKey, Boolean> trailingSlashes) {
		this.client = client;
		this.memoryId = memoryId;
		this.trailingSlashes = Map.copyOf(trailingSlashes);
		logger.info("AgentCoreLongTermMemoryRetriever initialized with memoryId: {}", memoryId);
	}

	/**
	 * Semantic search for memories with configurable namespace pattern.
	 * @param strategyId the memory strategy ID
	 * @param actorId the actor ID
	 * @param sessionId the session ID (required if pattern contains {sessionId})
	 * @param query the search query
	 * @param topK maximum number of results
	 * @param namespacePattern the namespace pattern to use
	 * @return list of matching memory records
	 */
	public List<MemoryRecord> searchMemories(String strategyId, String actorId, String sessionId, String query,
			int topK, String namespacePattern) {
		String namespace = this.requestNamespace(namespacePattern, strategyId, actorId, sessionId);
		return this.doSearch(namespace, strategyId, query, topK);
	}

	/**
	 * Semantic search for memories (actor-scoped, searches all sessions).
	 * @param strategyId the memory strategy id
	 * @param actorId the actor id
	 * @param query the natural-language query
	 * @param topK the maximum number of records to return
	 * @return matching memory records
	 */
	public List<MemoryRecord> searchMemories(String strategyId, String actorId, String query, int topK) {
		return this.searchMemories(strategyId, actorId, null, query, topK,
				AgentCoreLongTermMemoryNamespace.ACTOR.getPattern());
	}

	/**
	 * List all memories for an actor (no semantic search), for a namespace pattern
	 * without {@code {sessionId}}. Delegates to
	 * {@link #listMemories(String, String, String, String)} with a {@code null} session.
	 * @param strategyId the memory strategy id
	 * @param actorId the actor id
	 * @param namespacePattern the namespace pattern to scope the listing
	 * @return the matching memory records among those read
	 */
	public List<MemoryRecord> listMemories(String strategyId, String actorId, String namespacePattern) {
		return this.listMemories(strategyId, actorId, null, namespacePattern);
	}

	/**
	 * List all memories for an actor (no semantic search). Used for preferences.
	 *
	 * <p>
	 * Follows {@code nextToken} through the {@code ListMemoryRecords} pages, up to a
	 * fixed number of pages of the largest page size the service accepts, and logs a
	 * warning if more records remain. The cap counts records read, including records the
	 * namespace filter then drops.
	 * @param strategyId the memory strategy id
	 * @param actorId the actor id
	 * @param sessionId the session id (required if the pattern contains {sessionId})
	 * @param namespacePattern the namespace pattern to scope the listing
	 * @return the matching memory records among those read
	 */
	public List<MemoryRecord> listMemories(String strategyId, String actorId, String sessionId,
			String namespacePattern) {
		String namespace = this.requestNamespace(namespacePattern, strategyId, actorId, sessionId);
		logger.debug("Listing memories: namespace={}", namespace);

		try {
			List<MemoryRecordSummary> summaries = new ArrayList<>();
			String nextToken = null;
			int pages = 0;
			do {
				ListMemoryRecordsRequest request = ListMemoryRecordsRequest.builder()
					.memoryId(this.memoryId)
					.namespace(namespace)
					.memoryStrategyId(strategyId)
					.maxResults(SERVICE_MAX_RESULTS)
					.nextToken(nextToken)
					.build();

				ListMemoryRecordsResponse response = this.client.listMemoryRecords(request);
				summaries.addAll(response.memoryRecordSummaries());
				nextToken = response.nextToken();
				pages++;
			}
			while (nextToken != null && pages < MAX_LIST_PAGES);

			if (nextToken != null) {
				logger.warn("Stopped listing memories after {} pages ({} records); more remain: namespace={}", pages,
						summaries.size(), namespace);
			}
			List<MemoryRecord> records = this.extractRecords(summaries, namespace);
			logger.debug("Found {} memories in namespace: {}", records.size(), namespace);
			return records;
		}
		catch (Exception ex) {
			throw new AgentCoreMemoryException.RetrievalException("Failed to list memories: namespace=" + namespace,
					ex);
		}
	}

	private List<MemoryRecord> doSearch(String namespace, String strategyId, String query, int topK) {
		logger.debug("Searching: namespace={}, query={}, topK={}", namespace, query, topK);

		try {
			RetrieveMemoryRecordsRequest request = RetrieveMemoryRecordsRequest.builder()
				.memoryId(this.memoryId)
				.namespace(namespace)
				.searchCriteria(
						SearchCriteria.builder().searchQuery(query).memoryStrategyId(strategyId).topK(topK).build())
				.build();

			RetrieveMemoryRecordsResponse response = this.client.retrieveMemoryRecords(request);
			List<MemoryRecord> records = this.extractRecords(response.memoryRecordSummaries(), namespace);
			logger.debug("Found {} memories for query: {}", records.size(), query);
			return records;
		}
		catch (Exception ex) {
			throw new AgentCoreMemoryException.RetrievalException("Failed to search memories: namespace=" + namespace,
					ex);
		}
	}

	private List<MemoryRecord> extractRecords(List<MemoryRecordSummary> summaries, String namespace) {
		if (summaries == null || summaries.isEmpty()) {
			return List.of();
		}
		List<MemoryRecord> records = new ArrayList<>();
		int dropped = 0;
		for (MemoryRecordSummary summary : summaries) {
			if (!isInNamespace(summary, namespace)) {
				// Details at DEBUG only: the namespaces contain other actors' IDs
				logger.debug("Dropping memory record {} outside requested namespace {}: {}", summary.memoryRecordId(),
						namespace, summary.namespaces());
				dropped++;
				continue;
			}
			String contentText = (summary.content() != null) ? summary.content().text() : "";
			records.add(new MemoryRecord(summary.memoryRecordId(), contentText,
					(summary.score() != null) ? summary.score() : 0.0));
		}
		if (dropped > 0) {
			logger.warn("Dropped {} memory records outside requested namespace {}", dropped, namespace);
		}
		return records;
	}

	/**
	 * Resolves the namespace pattern. If validation matched the pattern to a memory
	 * namespace, the result ends with {@code /} exactly when that namespace does:
	 * AgentCore Memory stores namespaces as written in the template, and a request with a
	 * different trailing slash matches nothing. Otherwise the resolved namespace is sent
	 * as is.
	 * @param pattern the namespace pattern
	 * @param strategyId the memory strategy id
	 * @param actorId the actor id
	 * @param sessionId the session id, or {@code null} if the pattern has none
	 * @return the namespace to send to AgentCore Memory
	 */
	private String requestNamespace(String pattern, String strategyId, String actorId, String sessionId) {
		String namespace = AgentCoreLongTermMemoryNamespace.buildNamespace(pattern, strategyId, actorId, sessionId);
		Boolean trailingSlash = this.trailingSlashes.get(new NamespaceKey(strategyId, pattern));
		if (trailingSlash == null || trailingSlash == namespace.endsWith("/")) {
			return namespace;
		}
		return (trailingSlash) ? namespace + "/" : namespace.substring(0, namespace.length() - 1);
	}

	/**
	 * Whether any of the record's namespaces is the requested namespace or a descendant
	 * of it. Records without namespaces are rejected.
	 * @param summary the memory record returned by the service
	 * @param namespace the resolved namespace that was requested
	 * @return {@code true} if the record belongs to the requested namespace
	 */
	private static boolean isInNamespace(MemoryRecordSummary summary, String namespace) {
		if (!summary.hasNamespaces()) {
			return false;
		}
		String parent = (namespace.endsWith("/")) ? namespace : namespace + "/";
		return summary.namespaces()
			.stream()
			.anyMatch((actual) -> actual != null && (actual.equals(namespace) || actual.startsWith(parent)));
	}

	public record MemoryRecord(String id, String content, double score) {
	}

	/**
	 * A strategy ID with one of its namespace patterns, as validated at startup.
	 *
	 * @param strategyId the memory strategy ID
	 * @param pattern the namespace pattern
	 */
	record NamespaceKey(String strategyId, String pattern) {
	}

}
