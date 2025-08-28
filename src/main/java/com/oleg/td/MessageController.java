package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class MessageController {

    public static final class SendRequest {
        public String chat;
        public String text;
    }

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /** Пример: POST /send  { "chat": "@username", "text": "Привет!" } */
    @PostMapping("/send")
    public ResponseEntity<ObjectNode> send(@RequestBody SendRequest req) {
        if (req == null || req.chat == null || req.chat.isBlank() || req.text == null) {
            return ResponseEntity.badRequest().build();
        }
        ObjectNode resp = messageService.sendText(req.chat.trim(), req.text);
        return ResponseEntity.ok(resp);
    }
}
