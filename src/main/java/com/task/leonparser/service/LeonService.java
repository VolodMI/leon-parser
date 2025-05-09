package com.task.leonparser.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.task.leonparser.client.LeonApiClient;
import com.task.leonparser.config.LeonParserProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeonService {

    private final LeonApiClient client;
    private final LeonParserProperties properties;

    private static final DateTimeFormatter UTC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                    .withZone(ZoneOffset.UTC);

    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    public void startParsing() {
        JsonNode sportsJson = client.getSports().block();

        if (sportsJson == null || !sportsJson.isArray()) {
            log.warn("No sports data received");
            return;
        }

        List<CompletableFuture<Void>> leagueFutures = new ArrayList<>();

        for (JsonNode sport : sportsJson) {
            String sportName = sport.path("name").asText();

            if (properties.getTargetSports().contains(sportName)) {
                for (JsonNode region : sport.path("regions")) {
                    for (JsonNode league : region.path("leagues")) {
                        boolean isTop = league.path("top").asBoolean();

                        if (!properties.isTopLeaguesOnly() || isTop) {
                            LeagueNode leagueNode = new LeagueNode(
                                    sportName,
                                    league.path("name").asText(),
                                    league.path("id").asLong()
                            );
                            leagueFutures.add(processLeagueAsync(leagueNode));
                        }
                    }
                }
            }
        }

        CompletableFuture.allOf(leagueFutures.toArray(new CompletableFuture[0])).join();

        shutdownExecutor();
        log.info("Parsing completed");
    }

    private CompletableFuture<Void> processLeagueAsync(LeagueNode league) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.getMatchesByLeague(league.leagueId()).block();
            } catch (Exception e) {
                log.error("Failed to load matches for league {}", league, e);
                return null;
            }
        }, executor).thenCompose(matchesJson -> {
            List<CompletableFuture<Void>> matchFutures = new ArrayList<>();

            if (matchesJson != null && matchesJson.path("events").isArray()) {
                int count = 0;
                for (JsonNode event : matchesJson.path("events")) {
                    if (count++ >= properties.getMatchesLimit()) break;
                    long eventId = event.path("id").asLong();
                    matchFutures.add(processEventAsync(league, eventId));
                }
            }

            return CompletableFuture.allOf(matchFutures.toArray(new CompletableFuture[0]));
        });
    }

    private CompletableFuture<Void> processEventAsync(LeagueNode league, long eventId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.getFullEventInfo(eventId).block();
            } catch (Exception e) {
                log.error("Failed to load event info for eventId {}", eventId, e);
                return null;
            }
        }, executor).thenAccept(event -> {
            if (event != null) {
                printEvent(league, event);
            }
        });
    }

    private void printEvent(LeagueNode league, JsonNode event) {
        String matchName = event.path("name").asText();
        long kickoffTimestamp = event.path("kickoff").asLong();
        Instant kickoffInstant = kickoffTimestamp > 9999999999L
                ? Instant.ofEpochMilli(kickoffTimestamp)
                : Instant.ofEpochSecond(kickoffTimestamp);

        String kickoffTime = UTC_FORMATTER.format(kickoffInstant);
        long eventId = event.path("id").asLong();

        System.out.println(indent(0) + league.sportName() + ", " + league.leagueName());
        System.out.println(indent(1) + matchName + ", " + kickoffTime + ", " + eventId);

        for (JsonNode market : event.path("markets")) {
            String marketName = market.path("name").asText();
            System.out.println(indent(2) + marketName);

            for (JsonNode runner : market.path("runners")) {
                String outcomeName = runner.path("name").asText();
                double price = runner.path("price").asDouble();
                long outcomeId = runner.path("id").asLong();
                System.out.println(indent(3) + outcomeName + ", " + price + ", " + outcomeId);
            }
        }

        System.out.println();
    }

    private void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private String indent(int level) {
        return " ".repeat(level * 4);
    }

    private record LeagueNode(String sportName, String leagueName, long leagueId) {
    }
}

