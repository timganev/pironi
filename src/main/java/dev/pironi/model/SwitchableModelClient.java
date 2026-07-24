package dev.pironi.model;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class SwitchableModelClient implements ModelClient {
    private volatile String model;
    private volatile ModelClient delegate;

    public SwitchableModelClient(String model, ModelClient delegate) {
        switchTo(model, delegate);
    }

    @Override
    public ModelResponse chat(List<ChatMessage> messages)
            throws IOException, InterruptedException {
        return delegate.chat(messages);
    }

    public String model() {
        return model;
    }

    public void switchTo(String model, ModelClient delegate) {
        this.model = Objects.requireNonNull(model, "model");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
}
