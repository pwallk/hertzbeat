/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hertzbeat.alert.notice.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.alert.notice.AlertNoticeException;
import org.apache.hertzbeat.common.entity.alerter.GroupAlert;
import org.apache.hertzbeat.common.entity.alerter.NoticeReceiver;
import org.apache.hertzbeat.common.entity.alerter.NoticeTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Send alarm information by Telegram Bot
 */
@Component
@Slf4j
final class TelegramBotAlertNotifyHandlerImpl extends AbstractAlertNotifyHandlerImpl {

    @Override
    public void send(NoticeReceiver receiver, NoticeTemplate noticeTemplate, GroupAlert alert)
            throws AlertNoticeException {
        try {
            String token = receiver.getTgBotToken();
            if (!isValidTelegramToken(token)) {
                throw new AlertNoticeException("Invalid Telegram Bot Token");
            }
            String url = String.format(alerterProperties.getTelegramWebhookUrl(), token);
            TelegramBotNotifyDTO notifyBody = TelegramBotNotifyDTO.builder()
                    .chatId(receiver.getTgUserId())
                    .text(renderContent(noticeTemplate, alert))
                    .messageThreadId(receiver.getTgMessageThreadId())
                    .disableWebPagePreview(true)
                    .build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<TelegramBotNotifyDTO> telegramEntity = new HttpEntity<>(notifyBody, headers);
            ResponseEntity<TelegramBotNotifyResponse> entity = restTemplate.postForEntity(url, telegramEntity,
                    TelegramBotNotifyResponse.class);
            if (entity.getStatusCode() == HttpStatus.OK && entity.getBody() != null) {
                TelegramBotNotifyResponse body = entity.getBody();
                if (body.ok) {
                    log.debug("Send Telegram Bot Success");
                } else {
                    log.warn("Send Telegram Bot Failed: {}, error_code: {}", body.description, body.errorCode);
                    throw new AlertNoticeException(body.description);
                }
            } else {
                log.warn("Send Telegram Bot Failed {}", entity.getBody());
                throw new AlertNoticeException("Http StatusCode " + entity.getStatusCode());
            }
        } catch (Exception e) {
            throw new AlertNoticeException("[Telegram Bot Notify Error] " + e.getMessage());
        }
    }

    @Override
    public byte type() {
        return 7;
    }

    @Data
    @Builder
    private static class TelegramBotNotifyDTO {
        @JsonProperty("chat_id")
        private String chatId;
        private String text;
        @JsonProperty("disable_web_page_preview")
        private Boolean disableWebPagePreview;
        @JsonProperty("message_thread_id")
        private String messageThreadId;
    }

    @NoArgsConstructor
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TelegramBotNotifyResponse {
        private boolean ok;
        @JsonProperty("error_code")
        private Integer errorCode;
        private String description;
    }

    private boolean isValidTelegramToken(String token) {
        // Adjusted pattern to match real Telegram Bot tokens like
        // 110201543:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw
        String tokenPattern = "^[0-9]+:[a-zA-Z0-9_-]+$";
        return token != null && token.matches(tokenPattern);
    }
}
