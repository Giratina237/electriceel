/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.text;

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.decoder.SimpleDecoder;
import com.google.android.exoplayer2.util.Log;
import java.nio.ByteBuffer;

/**
 * Base class for subtitle parsers that use their own decode thread.
 */
public abstract class SimpleSubtitleDecoder extends
    SimpleDecoder<SubtitleInputBuffer, SubtitleOutputBuffer, SubtitleDecoderException> implements
    SubtitleDecoder {

  private static final String TAG = "SimpleSubtitleDecoder";

  private final String name;
  // MOD: see setTransformer
  @Nullable private volatile SubtitleTransformer transformer;

  /**
   * @param name The name of the decoder.
   */
  protected SimpleSubtitleDecoder(String name) {
    super(new SubtitleInputBuffer[2], new SubtitleOutputBuffer[2]);
    this.name = name;
    setInitialInputBufferSize(1024);
  }

  /**
   * MOD: Changes what's decoded before it's shown, from the next sample on. A transformer that fails leaves it as it was.
   */
  public void setTransformer(@Nullable SubtitleTransformer transformer) {
    this.transformer = transformer;
  }

  @Override
  public final String getName() {
    return name;
  }

  @Override
  public void setPositionUs(long timeUs) {
    // Do nothing
  }

  @Override
  protected final SubtitleInputBuffer createInputBuffer() {
    return new SubtitleInputBuffer();
  }

  @Override
  protected final SubtitleOutputBuffer createOutputBuffer() {
    return new SimpleSubtitleOutputBuffer(this);
  }

  @Override
  protected final SubtitleDecoderException createUnexpectedDecodeException(Throwable error) {
    return new SubtitleDecoderException("Unexpected decode error", error);
  }

  @Override
  protected final void releaseOutputBuffer(SubtitleOutputBuffer buffer) {
    super.releaseOutputBuffer(buffer);
  }

  @SuppressWarnings("ByteBufferBackingArray")
  @Override
  @Nullable
  protected final SubtitleDecoderException decode(
      SubtitleInputBuffer inputBuffer, SubtitleOutputBuffer outputBuffer, boolean reset) {
    try {
      ByteBuffer inputData = inputBuffer.data;
      Subtitle subtitle = decode(inputData.array(), inputData.limit(), reset);
      // MOD: see setTransformer. Its failure would otherwise stop the video, as a decode error does.
      SubtitleTransformer transformer = this.transformer;
      if (transformer != null) {
        try {
          Subtitle transformed = transformer.transform(subtitle);
          if (transformed != null) {
            subtitle = transformed;
          }
        } catch (RuntimeException e) {
          Log.e(TAG, "Subtitle transformer failed: " + name, e);
        }
      }
      outputBuffer.setContent(inputBuffer.timeUs, subtitle, inputBuffer.subsampleOffsetUs);
      // Clear BUFFER_FLAG_DECODE_ONLY (see [Internal: b/27893809]).
      outputBuffer.clearFlag(C.BUFFER_FLAG_DECODE_ONLY);
      return null;
    } catch (SubtitleDecoderException e) {
      return e;
    }
  }

  /**
   * Decodes data into a {@link Subtitle}.
   *
   * @param data An array holding the data to be decoded, starting at position 0.
   * @param size The size of the data to be decoded.
   * @param reset Whether the decoder must be reset before decoding.
   * @return The decoded {@link Subtitle}.
   * @throws SubtitleDecoderException If a decoding error occurs.
   */
  protected abstract Subtitle decode(byte[] data, int size, boolean reset)
      throws SubtitleDecoderException;

}
