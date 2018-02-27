package com.bot.cinemabot;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import com.bot.cinemabot.model.CinemaItem;
import com.bot.cinemabot.model.CinemaMallItem;
import com.bot.cinemabot.model.CinemaResponse;
import com.bot.cinemabot.model.DisplayItem;
import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class LotteCinemaScheduler {

    final private Gson gson = new Gson();

    final private AtomicInteger callCount = new AtomicInteger(0);
    final private AtomicInteger cacheAllTicketsCount = new AtomicInteger(0);
    private List<CinemaItem> cache1p1Tickets;

    @Autowired
    private Telegram telegram;

    @Value("${cinema.lotte.api}")
    private String lotte;

    @PostConstruct
    public void init() {
        CinemaResponse data = getCinemaData();
        CinemaMallItem cinemaMallItems = data.getCinemaMallItemLists();
        int allTicketsCount = allTicketsCount(cinemaMallItems);
        List<CinemaItem> onePlusOneTickets = get1p1Tickets(cinemaMallItems);
        cache1p1Tickets = Collections.synchronizedList(onePlusOneTickets);

        telegram.sendMessageToBot("롯데시네마\n모든 관람권: %s\n1+1 관람권: %s",
                allTicketsCount, onePlusOneTickets.size());
    }

    @Scheduled(initialDelay = 1_000, fixedDelayString = "${schedule.fixedDelay}")
    public void aJob() {
        CinemaResponse data = getCinemaData();
        CinemaMallItem cinemaMallItems = data.getCinemaMallItemLists();
        List<CinemaItem> onePlusOneTickets = get1p1Tickets(cinemaMallItems);
        int allTicketsCount = allTicketsCount(cinemaMallItems);

        updateCache(allTicketsCount);

        boolean isChangedTicket = isChangedTicket(onePlusOneTickets);

        if (allTicketsCount == -1) {
            log.debug(gson.toJson(data));
        } else if (isChangedTicket) {
            CinemaItem movieItem = getNew1p1Ticket(cinemaMallItems);
            if (!StringUtils.isEmpty(movieItem.getDisplayItemName())) {
                String buyLink = String.format(
                        "http://www.lottecinema.co.kr/LCHS/Contents/Cinema-Mall/gift-shop-detail.aspx?displayItemID=%s&displayMiddleClassification=%s",
                        movieItem.getDisplayItemID(), movieItem.getDisplayMiddleClassificationCode()
                );
                telegram.sendMessageToChannel("%s\n%s\n%s원\n1+1관람권:%s, 영화관람권:%s\n구매링크:%s\n\n이미지:%s",
                        movieItem.getDisplayItemName(), movieItem.getUseRestrictionsDayName(), movieItem.getDiscountSellPrice(),
                        onePlusOneTickets.size(), cacheAllTicketsCount, buyLink, movieItem.getItemImageUrl()
                );
            }
            updateCache(onePlusOneTickets);
        }

        log.info("호출횟수:{}, 영화관람권:{}({}), 1+1관람권:{}({}), isChangedTicket:{}",
                callCount.incrementAndGet(), allTicketsCount, cacheAllTicketsCount, onePlusOneTickets.size(), cache1p1Tickets.size(),
                isChangedTicket);
    }

    private boolean isChangedTicket(List<CinemaItem> newTickets) {
        boolean a = !newTickets
                .stream()
                .allMatch(newTicket ->
                        cache1p1Tickets.stream().anyMatch(oldTicket -> oldTicket.getDisplayItemName().equals(newTicket.getDisplayItemName()))
                );
        boolean b = !cache1p1Tickets
                .stream()
                .allMatch(oldTicket ->
                        newTickets.stream().anyMatch(newTicket -> newTicket.getDisplayItemName().equals(oldTicket.getDisplayItemName()))
                );
        return a || b;
    }

    private int allTicketsCount(CinemaMallItem cinemaMallItems) {
        return cinemaMallItems.getCinemaMallClassifications().getItems()
                .stream()
                .filter(item -> "20".equals(item.getDisplayLargeClassificationCode()))
                .filter(item -> "10".equals(item.getDisplayMiddleClassificationCode()))
                .findFirst()
                .map(DisplayItem::getItemCount)
                .orElse(-1);
    }

    private List<CinemaItem> get1p1Tickets(CinemaMallItem cinemaMallItems) {
        return cinemaMallItems.getItems().getItems()
                .stream()
                .filter(item -> "20".equals(item.getDisplayLargeClassificationCode()))
                .filter(item -> "10".equals(item.getDisplayMiddleClassificationCode()))
                .filter(item -> "영화관람권".equals(item.getDisplayMiddleClassificationName()))
                .filter(item -> item.getDisplayItemName().contains("1+1") || item.getDisplayItemName().contains("얼리버드"))
                .collect(Collectors.toList());
    }

    private CinemaItem getNew1p1Ticket(CinemaMallItem cinemaMallItems) {
        return get1p1Tickets(cinemaMallItems)
                .stream()
                .filter(newTicket -> cache1p1Tickets.stream()
                        .noneMatch(oldTicket -> oldTicket.getDisplayItemName().equals(newTicket.getDisplayItemName())))
                .findFirst()
                .orElse(new CinemaItem());
    }

    private CinemaResponse getCinemaData() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE);

        MultiValueMap<String, String> map = new LinkedMultiValueMap<String, String>();
        map.add("paramList",
                "{\"MethodName\":\"CinemaMallGiftItemList\",\"channelType\":\"HO\",\"osType\":\"Chrome\",\"osVersion\":\"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/63.0.3239.132 Safari/537.36\",\"multiLanguageID\":\"KR\",\"classificationCode\":\"20\"}");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<MultiValueMap<String, String>>(map, headers);

        String jsonResponse = telegram.restTemplate.postForObject(lotte, request, String.class);
        return gson.fromJson(jsonResponse, CinemaResponse.class);
    }

    private void updateCache(int allTicketsCount) {
        if (cacheAllTicketsCount.get() != allTicketsCount) {
            cacheAllTicketsCount.set(allTicketsCount);
        }
    }

    private void updateCache(List<CinemaItem> tickets) {
        cache1p1Tickets.clear();
        cache1p1Tickets.addAll(tickets);
    }

}

//        https://memorynotfound.com/spring-boot-passing-command-line-arguments-example/
//        java -jar command-line.jar \
//            this-is-a-non-option-arg \
//            --server.port=9090 \
//            --person.name=Memorynotfound.com