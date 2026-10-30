package com.pylearn.backend.config;

import com.pylearn.backend.websocket.PythonExecutionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final PythonExecutionWebSocketHandler pythonHandler;

    public WebSocketConfig(PythonExecutionWebSocketHandler pythonHandler) {
        this.pythonHandler = pythonHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(pythonHandler, "/ws")
                .setAllowedOriginPatterns("*");
    }
}
