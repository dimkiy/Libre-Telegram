package org.telegram.messenger;

import android.os.Build;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.Candidate;
import com.google.mlkit.genai.prompt.GenerateContentResponse;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;

public final class PrizmaAi {
    public interface Listener {
        void onStage(String stage);

        void onResult(String text);

        void onError(String reason);
    }

    private static GenerativeModelFutures model;

    private PrizmaAi() {
    }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 26;
    }

    private static GenerativeModelFutures model() {
        if (model == null) {
            model = GenerativeModelFutures.from(Generation.INSTANCE.getClient());
        }
        return model;
    }

    public static void ask(String prompt, Listener listener) {
        if (!supported()) {
            post(() -> listener.onError("Нужен Android 8 или новее"));
            return;
        }
        try {
            then(model().checkStatus(), status -> {
                if (status == FeatureStatus.AVAILABLE) {
                    generate(prompt, listener);
                } else if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                    post(() -> listener.onStage("Скачиваю модель"));
                    then(model().download(new DownloadCallback() {
                        @Override
                        public void onDownloadStarted(long bytes) {
                        }

                        @Override
                        public void onDownloadProgress(long bytes) {
                        }

                        @Override
                        public void onDownloadCompleted() {
                        }

                        @Override
                        public void onDownloadFailed(GenAiException e) {
                            post(() -> listener.onError("Модель не скачалась"));
                        }
                    }), done -> generate(prompt, listener), listener);
                } else {
                    post(() -> listener.onError("Этот телефон не поддерживает Gemini Nano"));
                }
            }, listener);
        } catch (Throwable e) {
            post(() -> listener.onError("Gemini Nano недоступен на этом телефоне"));
        }
    }

    private static void generate(String prompt, Listener listener) {
        post(() -> listener.onStage("Думаю"));
        then(model().generateContent(prompt), response -> {
            String text = text(response);
            post(() -> {
                if (text == null || text.trim().isEmpty()) {
                    listener.onError("Модель ничего не ответила");
                } else {
                    listener.onResult(text.trim());
                }
            });
        }, listener);
    }

    private static String text(GenerateContentResponse response) {
        if (response == null || response.getCandidates() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Candidate c : response.getCandidates()) {
            if (c.getText() != null) sb.append(c.getText());
            break;
        }
        return sb.toString();
    }

    private interface Step<T> {
        void run(T value);
    }

    private static <T> void then(ListenableFuture<T> future, Step<T> step, Listener listener) {
        Futures.addCallback(future, new FutureCallback<T>() {
            @Override
            public void onSuccess(T result) {
                step.run(result);
            }

            @Override
            public void onFailure(Throwable t) {
                FileLog.e(t);
                post(() -> listener.onError("Gemini Nano недоступен на этом телефоне"));
            }
        }, MoreExecutors.directExecutor());
    }

    private static void post(Runnable r) {
        AndroidUtilities.runOnUIThread(r);
    }
}
