package com.igirepay.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequestMapping("/api")
public class PaymentController {
    // Stores: idempotency_key -> saved response
    private final ConcurrentHashMap<String, ResponseEntity<Map<String, Object>>> responseCache = new ConcurrentHashMap<>();

    // Stores: idempotency_key -> original request body (for conflict detection)
    private final ConcurrentHashMap<String, Map<String, Object>> requestCache = new ConcurrentHashMap<>();

    // Stores: idempotency_key -> lock (for race condition handling)
    private final ConcurrentHashMap<String, ReentrantLock> lockMap = new ConcurrentHashMap<>();

    @PostMapping("/process-payment")
    public ResponseEntity<Map<String, Object>> processPayment(
            @RequestHeader(value = "Idempotency-key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> requestBody) throws InterruptedException {

         //  GUARD: Missing Idempotency-Key header
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("Error", "Missing Idempotency-key header"));
        }

        // Get or create a lock for this key
        lockMap.putIfAbsent(idempotencyKey, new ReentrantLock());
        ReentrantLock lock = lockMap.get(idempotencyKey);

        // Acquire the lock — blocks duplicate requests until we're done
        lock.lock();

        try {
            // Case 1: Key already exists
            if(responseCache.containsKey(idempotencyKey)){
                //Check if request body is different
                Map<String, Object> originalBody = requestCache.get(idempotencyKey);
                if(!originalBody.equals(requestBody)) {
                    return ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Idempotency key already used for different request body"));
                }

                //same body return cached response
                ResponseEntity<Map<String,Object>> cached = responseCache.get(idempotencyKey);
                return ResponseEntity
                    .status(cached.getStatusCode())
                    .header("X-Cache-Hit", "true")
                    .body(cached.getBody());
            }

            // Case 2: New request , process it
            // save the request body first
            requestCache.put(idempotencyKey, requestBody);

            Thread.sleep(2000);

            // EXtract amount and currency from request body
            Object amount = requestBody.get("amount");
            Object currency = requestBody.get("currency");

            // Build the response
            Map<String, Object> responseBody = Map.of(
                "status", "success",
                "message", String.format("Charged %s %s", amount, currency),
                "idempotencyKey", idempotencyKey
            );

            ResponseEntity<Map<String, Object>> response = ResponseEntity
                .status(HttpStatus.CREATED)
                .body(responseBody);
            responseCache.put(idempotencyKey, response);

            return response;
        } finally {
            lock.unlock();
        }
    }
}