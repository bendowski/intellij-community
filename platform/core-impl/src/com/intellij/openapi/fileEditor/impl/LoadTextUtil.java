/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.fileEditor.impl;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.BinaryFileDecompiler;
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers;
import com.intellij.openapi.fileTypes.CharsetUtil;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.encoding.EncodingManager;
import com.intellij.openapi.vfs.encoding.EncodingRegistry;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.NotNullFunction;
import com.intellij.util.ObjectUtils;
import com.intellij.util.text.ByteArrayCharSequence;
import com.intellij.util.text.CharArrayUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

public final class LoadTextUtil {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.fileEditor.impl.LoadTextUtil");

  public enum AutoDetectionReason { FROM_BOM, FROM_BYTES }

  private static final int UNLIMITED = -1;

  private LoadTextUtil() { }

  @NotNull
  private static ConvertResult convertLineSeparatorsToSlashN(@NotNull CharBuffer buffer) {
    int dst = 0;
    char prev = ' ';
    int crCount = 0;
    int lfCount = 0;
    int crlfCount = 0;

    final int length = buffer.length();
    final char[] bufferArray = CharArrayUtil.fromSequenceWithoutCopying(buffer);

    for (int src = 0; src < length; src++) {
      char c = bufferArray != null ? bufferArray[src]:buffer.charAt(src);
      switch (c) {
        case '\r':
          if(bufferArray != null) bufferArray[dst++] = '\n';
          else buffer.put(dst++, '\n');
          crCount++;
          break;
        case '\n':
          if (prev == '\r') {
            crCount--;
            crlfCount++;
          }
          else {
            if(bufferArray != null) bufferArray[dst++] = '\n';
            else buffer.put(dst++, '\n');
            lfCount++;
          }
          break;
        default:
          if(bufferArray != null) bufferArray[dst++] = c;
          else buffer.put(dst++, c);
          break;
      }
      prev = c;
    }

    String detectedLineSeparator = guessLineSeparator(crCount, lfCount, crlfCount);

    CharSequence result = buffer.length() == dst ? buffer : buffer.subSequence(0, dst);
    return new ConvertResult(result, detectedLineSeparator);
  }

  @NotNull
  private static ConvertResult convertLineSeparatorsToSlashN(@NotNull byte[] charsAsBytes, int startOffset, int endOffset) {
    int index = ArrayUtil.indexOf(charsAsBytes, (byte)'\r', startOffset, endOffset);
    if (index == -1) {
      // optimisation: if there is no CR in the file, no line separator conversion is necessary. we can re-use the passed byte buffer in place
      ByteArrayCharSequence sequence = new ByteArrayCharSequence(charsAsBytes, startOffset, endOffset);
      String detectedLineSeparator = ArrayUtil.indexOf(charsAsBytes, (byte)'\n', startOffset, endOffset) == -1 ? null : "\n";
      return new ConvertResult(sequence, detectedLineSeparator);
    }
    int dst = 0;
    char prev = ' ';
    int crCount = 0;
    int lfCount = 0;
    int crlfCount = 0;
    byte[] result = new byte[endOffset-startOffset];

    for (int src = startOffset; src < endOffset; src++) {
      char c = (char)charsAsBytes[src];
      switch (c) {
        case '\r':
          result[dst++] = '\n';
          
          crCount++;
          break;
        case '\n':
          if (prev == '\r') {
            crCount--;
            crlfCount++;
          }
          else {
            result[dst++] = '\n';
            lfCount++;
          }
          break;
        default:
          result[dst++] = (byte)c;
          break;
      }
      prev = c;
    }

    String detectedLineSeparator = guessLineSeparator(crCount, lfCount, crlfCount);

    ByteArrayCharSequence sequence = new ByteArrayCharSequence(result, 0, dst);
    return new ConvertResult(sequence, detectedLineSeparator);
  }

  @Nullable
  private static String guessLineSeparator(int crCount, int lfCount, int crlfCount) {
    String detectedLineSeparator = null;
    if (crlfCount > crCount && crlfCount > lfCount) {
      detectedLineSeparator = "\r\n";
    }
    else if (crCount > lfCount) {
      detectedLineSeparator = "\r";
    }
    else if (lfCount > 0) {
      detectedLineSeparator = "\n";
    }
    return detectedLineSeparator;
  }

  // private fake charsets for files which have one-byte-for-ascii-characters encoding but contain seven bits characters only. used for optimization since we don't have to encode-decode bytes here.
  private static final Charset INTERNAL_SEVEN_BIT_UTF8 = new SevenBitCharset(CharsetToolkit.UTF8_CHARSET);
  private static final Charset INTERNAL_SEVEN_BIT_ISO_8859_1 = new SevenBitCharset(CharsetToolkit.ISO_8859_1_CHARSET);
  private static final Charset INTERNAL_SEVEN_BIT_WIN_1251 = new SevenBitCharset(CharsetToolkit.WIN_1251_CHARSET);
  private static class SevenBitCharset extends Charset {
    private final Charset myBaseCharset;

    /**
     * should be {@code this.name().contains(CharsetToolkit.UTF8)} for {@link #getOverriddenCharsetByBOM(byte[], Charset)} to work
     */
    SevenBitCharset(Charset baseCharset) {
      super("IJ__7BIT_"+baseCharset.name(), ArrayUtil.EMPTY_STRING_ARRAY);
      myBaseCharset = baseCharset;
    }

    @Override
    public boolean contains(Charset cs) {
      throw new IllegalStateException();
    }

    @Override
    public CharsetDecoder newDecoder() {
      throw new IllegalStateException();
    }

    @Override
    public CharsetEncoder newEncoder() {
      throw new IllegalStateException();
    }
  }

  public static class DetectResult {
    public final Charset hardCodedCharset;
    public final CharsetToolkit.GuessedEncoding guessed;
    @Nullable public final byte[] BOM;

    DetectResult(Charset hardCodedCharset, CharsetToolkit.GuessedEncoding guessed, @Nullable byte[] BOM) {
      this.hardCodedCharset = hardCodedCharset;
      this.guessed = guessed;
      this.BOM = BOM;
    }
  }

  // guess from file type or content
  @NotNull
  private static DetectResult detectHardCharset(@NotNull VirtualFile virtualFile,
                                                @NotNull byte[] content,
                                                int length,
                                                @NotNull FileType fileType) {
    String charsetName = fileType.getCharset(virtualFile, content);
    DetectResult guessed = guessFromContent(virtualFile, content, length);
    Charset hardCodedCharset = charsetName == null ? guessed.hardCodedCharset : CharsetToolkit.forName(charsetName);

    if (hardCodedCharset == null && guessed.guessed == CharsetToolkit.GuessedEncoding.VALID_UTF8) {
      return new DetectResult(CharsetToolkit.UTF8_CHARSET, guessed.guessed, guessed.BOM);
    }
    return new DetectResult(hardCodedCharset, guessed.guessed, guessed.BOM);
  }

  @NotNull
  public static Charset detectCharsetAndSetBOM(@NotNull VirtualFile virtualFile, @NotNull byte[] content, @NotNull FileType fileType) {
    Charset internalCharset = detectInternalCharsetAndSetBOM(virtualFile, content, content.length, true, fileType).hardCodedCharset;
    return internalCharset instanceof SevenBitCharset ? ((SevenBitCharset)internalCharset).myBaseCharset : internalCharset;
  }

  @NotNull
  private static Charset getDefaultCharsetFromEncodingManager(@NotNull VirtualFile virtualFile) {
    Charset specifiedExplicitly = EncodingRegistry.getInstance().getEncoding(virtualFile, true);
    return ObjectUtils.notNull(specifiedExplicitly, EncodingRegistry.getInstance().getDefaultCharset());
  }

  @NotNull
  private static DetectResult detectInternalCharsetAndSetBOM(@NotNull VirtualFile file,
                                                             @NotNull byte[] content,
                                                             int length,
                                                             boolean saveBOM,
                                                             @NotNull FileType fileType) {
    DetectResult info = detectHardCharset(file, content, length, fileType);

    Charset charset;
    if (info.hardCodedCharset == null) {
      charset = file.isCharsetSet() ? file.getCharset() : getDefaultCharsetFromEncodingManager(file);
    }
    else {
      charset = info.hardCodedCharset;
    }

    byte[] bom = info.BOM;
    if (saveBOM && bom != null && bom.length != 0) {
      file.setBOM(bom);
      setCharsetAutoDetectionReason(file, AutoDetectionReason.FROM_BOM);
    }

    file.setCharset(charset);

    Charset result = charset;
    // optimisation
    if (info.guessed == CharsetToolkit.GuessedEncoding.SEVEN_BIT) {
      if (charset == CharsetToolkit.UTF8_CHARSET) {
        result = INTERNAL_SEVEN_BIT_UTF8;
      }
      else if (charset == CharsetToolkit.ISO_8859_1_CHARSET) {
        result = INTERNAL_SEVEN_BIT_ISO_8859_1;
      }
      else if (charset == CharsetToolkit.WIN_1251_CHARSET) {
        result = INTERNAL_SEVEN_BIT_WIN_1251;
      }
    }

    return new DetectResult(result, info.guessed, bom);
  }


  @NotNull
  public static DetectResult guessFromContent(@NotNull VirtualFile virtualFile, @NotNull byte[] content) {
    return guessFromContent(virtualFile, content, content.length);
  }

  private static final boolean GUESS_UTF = Boolean.parseBoolean(System.getProperty("idea.guess.utf.encoding", "true"));
  @NotNull
  private static DetectResult guessFromContent(@NotNull VirtualFile virtualFile, @NotNull byte[] content, int length) {
    AutoDetectionReason detectedFromBytes = null;
    try {
      DetectResult info;
      if (GUESS_UTF) {
        info = guessFromBytes(content, 0, length, getDefaultCharsetFromEncodingManager(virtualFile));
        if (info.BOM != null) {
          detectedFromBytes = AutoDetectionReason.FROM_BOM;
        }
        else if (info.guessed == CharsetToolkit.GuessedEncoding.VALID_UTF8) {
          detectedFromBytes = AutoDetectionReason.FROM_BYTES;
        }
      }
      else {
        info = new DetectResult(null, null, null);
      }
      return info;
    }
    finally {
      setCharsetAutoDetectionReason(virtualFile, detectedFromBytes);
    }
  }

  @NotNull
  private static DetectResult guessFromBytes(@NotNull byte[] content,
                                             int startOffset, int endOffset,
                                             @NotNull Charset defaultCharset) {
    CharsetToolkit toolkit = new CharsetToolkit(content, defaultCharset);
    toolkit.setEnforce8Bit(true);
    Charset charset = toolkit.guessFromBOM();
    if (charset != null) {
      byte[] bom = ObjectUtils.notNull(CharsetToolkit.getMandatoryBom(charset), CharsetToolkit.UTF8_BOM);
      return new DetectResult(charset, null, bom);
    }
    CharsetToolkit.GuessedEncoding guessed = toolkit.guessFromContent(startOffset, endOffset);
    if (guessed == CharsetToolkit.GuessedEncoding.VALID_UTF8) {
      return new DetectResult(CharsetToolkit.UTF8_CHARSET, CharsetToolkit.GuessedEncoding.VALID_UTF8, null); //UTF detected, ignore all directives
    }
    return new DetectResult(null, guessed, null);
  }

  /**
   * Tries to detect text in the {@code bytes} and call the {@code fileTextProcessor} with the text (if detected) or with null if not
   */
  public static String getTextFromBytesOrNull(@NotNull byte[] bytes, int startOffset, int endOffset) {
    Charset defaultCharset = EncodingManager.getInstance().getDefaultCharset();
    DetectResult info = guessFromBytes(bytes, startOffset, endOffset, defaultCharset);
    Charset charset;
    if (info.hardCodedCharset != null) {
      charset = info.hardCodedCharset;
    }
    else {
      switch (info.guessed) {
        case SEVEN_BIT:
          charset = CharsetToolkit.US_ASCII_CHARSET;
          break;
        case VALID_UTF8:
          charset = CharsetToolkit.UTF8_CHARSET;
          break;
        case INVALID_UTF8:
        case BINARY:
          // the charset was not detected so the file is likely binary
          return null;
        default:
          throw new IllegalStateException(String.valueOf(info.guessed));
      }
    }
    byte[] bom = info.BOM;
    ConvertResult result = convertBytes(bytes, Math.min(startOffset + (bom == null ? 0 : bom.length), endOffset), endOffset, charset);
    return result.text.toString();
  }

  @NotNull
  private static Pair.NonNull<Charset,byte[]> getOverriddenCharsetByBOM(@NotNull byte[] content, @NotNull Charset charset) {
    if (charset.name().contains(CharsetToolkit.UTF8) && CharsetToolkit.hasUTF8Bom(content)) {
      return Pair.createNonNull(charset, CharsetToolkit.UTF8_BOM);
    }
    Charset charsetFromBOM = CharsetToolkit.guessFromBOM(content);
    if (charsetFromBOM != null) {
      byte[] bom = ObjectUtils.notNull(CharsetToolkit.getMandatoryBom(charsetFromBOM), ArrayUtil.EMPTY_BYTE_ARRAY);
      return Pair.createNonNull(charsetFromBOM, bom);
    }

    return Pair.createNonNull(charset, ArrayUtil.EMPTY_BYTE_ARRAY);
  }

  public static void changeLineSeparators(@Nullable Project project,
                                          @NotNull VirtualFile file,
                                          @NotNull String newSeparator,
                                          @NotNull Object requestor) throws IOException {
    CharSequence currentText = getTextByBinaryPresentation(file.contentsToByteArray(), file, true, false);
    String currentSeparator = detectLineSeparator(file, false);
    if (newSeparator.equals(currentSeparator)) {
      return;
    }
    String newText = StringUtil.convertLineSeparators(currentText.toString(), newSeparator);

    file.setDetectedLineSeparator(newSeparator);
    write(project, file, requestor, newText, -1);
  }

  /**
   * Overwrites file with text and sets modification stamp and time stamp to the specified values.
   * <p/>
   * Normally you should not use this method.
   *
   * @param requestor            any object to control who called this method. Note that
   *                             it is considered to be an external change if {@code requestor} is {@code null}.
   *                             See {@link VirtualFileEvent#getRequestor}
   * @param newModificationStamp new modification stamp or -1 if no special value should be set @return {@code Writer}
   * @throws IOException if an I/O error occurs
   * @see VirtualFile#getModificationStamp()
   */
  public static void write(@Nullable Project project,
                           @NotNull VirtualFile virtualFile,
                           @NotNull Object requestor,
                           @NotNull String text,
                           long newModificationStamp) throws IOException {
    Charset existing = virtualFile.getCharset();
    Pair.NonNull<Charset, byte[]> chosen = charsetForWriting(project, virtualFile, text, existing);
    Charset charset = chosen.first;
    byte[] buffer = chosen.second;
    if (!charset.equals(existing)) {
      virtualFile.setCharset(charset);
    }
    setDetectedFromBytesFlagBack(virtualFile, buffer);

    virtualFile.setBinaryContent(buffer, newModificationStamp, -1, requestor);
  }

  @NotNull
  public static Pair.NonNull<Charset, byte[]> charsetForWriting(@Nullable Project project,
                                                                @NotNull VirtualFile virtualFile,
                                                                @NotNull String text,
                                                                @NotNull Charset existing) {
    Charset specified = extractCharsetFromFileContent(project, virtualFile, text);
    Pair.NonNull<Charset, byte[]> chosen = chooseMostlyHarmlessCharset(existing, specified, text);
    Charset charset = chosen.first;

    // in case of "UTF-16", OutputStreamWriter sometimes adds BOM on it's own.
    // see http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6800103
    byte[] bom = virtualFile.getBOM();
    Charset fromBom = bom == null ? null : CharsetToolkit.guessFromBOM(bom);
    if (fromBom != null && !fromBom.equals(charset)) {
      chosen = Pair.createNonNull(fromBom, text.getBytes(fromBom));
    }
    return chosen;
  }

  private static void setDetectedFromBytesFlagBack(@NotNull VirtualFile virtualFile, @NotNull byte[] content) {
    if (virtualFile.getBOM() == null) {
      guessFromContent(virtualFile, content);
    }
    else {
      // prevent file to be reloaded in other encoding after save with BOM
      setCharsetAutoDetectionReason(virtualFile, AutoDetectionReason.FROM_BOM);
    }
  }

  @NotNull
  public static Pair.NonNull<Charset, byte[]> chooseMostlyHarmlessCharset(@NotNull Charset existing, @NotNull Charset specified, @NotNull String text) {
    try {
      if (specified.equals(existing)) {
        return Pair.createNonNull(specified, text.getBytes(existing));
      }

      byte[] out = isSupported(specified, text);
      if (out != null) {
        return Pair.createNonNull(specified, out); //if explicitly specified encoding is safe, return it
      }
      out = isSupported(existing, text);
      if (out != null) {
        return Pair.createNonNull(existing, out);   //otherwise stick to the old encoding if it's ok
      }
      return Pair.createNonNull(specified, text.getBytes(specified)); //if both are bad there is no difference
    }
    catch (RuntimeException e) {
      Charset defaultCharset = Charset.defaultCharset();
      return Pair.createNonNull(defaultCharset, text.getBytes(defaultCharset)); //if both are bad and there is no hope, use the default charset
    }
  }

  @Nullable("null means not supported, otherwise it is converted byte stream")
  private static byte[] isSupported(@NotNull Charset charset, @NotNull String str) {
    try {
      if (!charset.canEncode()) return null;
      byte[] bytes = str.getBytes(charset);
      if (!str.equals(new String(bytes, charset))) {
        return null;
      }

      return bytes;
    }
    catch (Exception e) {
      return null;//wow, some charsets throw NPE inside .getBytes() when unable to encode (JIS_X0212-1990)
    }
  }

  @NotNull
  public static Charset extractCharsetFromFileContent(@Nullable Project project, @NotNull VirtualFile virtualFile, @NotNull CharSequence text) {
    return ObjectUtils.notNull(charsetFromContentOrNull(project, virtualFile, text), virtualFile.getCharset());
  }

  @Nullable("returns null if cannot determine from content")
  public static Charset charsetFromContentOrNull(@Nullable Project project, @NotNull VirtualFile virtualFile, @NotNull CharSequence text) {
    return CharsetUtil.extractCharsetFromFileContent(project, virtualFile, virtualFile.getFileType(), text);
  }

  @NotNull
  public static CharSequence loadText(@NotNull final VirtualFile file) {
    FileType type = file.getFileType();
    if (type.isBinary()) {
      final BinaryFileDecompiler decompiler = BinaryFileTypeDecompilers.INSTANCE.forFileType(type);
      if (decompiler != null) {
        CharSequence text = decompiler.decompile(file);
        try {
          StringUtil.assertValidSeparators(text);
        }
        catch (AssertionError e) {
          LOG.error(e);
        }
        return text;
      }

      throw new IllegalArgumentException("Attempt to load text for binary file which doesn't have a decompiler plugged in: " +
                                         file.getPresentableUrl() + ". File type: " + type.getName());
    }
    return loadText(file, UNLIMITED);
  }

  /**
   * Loads content of given virtual file. If limit is {@value UNLIMITED} then full CharSequence will be returned. Else CharSequence
   * will be truncated by limit if it has bigger length.
   * @param file Virtual file for content loading
   * @param limit Maximum characters count or {@value UNLIMITED}
   * @throws IllegalArgumentException for binary files
   * @return Full or truncated CharSequence with file content
   */
  @NotNull
  public static CharSequence loadText(@NotNull final VirtualFile file, int limit) {
    FileType type = file.getFileType();
    if (type.isBinary()) throw new IllegalArgumentException(
      "Attempt to load truncated text for binary file: " + file.getPresentableUrl() + ". File type: " + type.getName()
    );

    if (file instanceof LightVirtualFile) {
      return limitCharSequence(((LightVirtualFile)file).getContent(), limit);
    }

    if (file.isDirectory()) {
      throw new AssertionError("'" + file.getPresentableUrl() + "' is a directory");
    }
    try {
      byte[] bytes = limit == UNLIMITED ? file.contentsToByteArray() :
                     FileUtil.loadFirstAndClose(file.getInputStream(), limit);
      return getTextByBinaryPresentation(bytes, file);
    }
    catch (IOException e) {
      return ArrayUtil.EMPTY_CHAR_SEQUENCE;
    }
  }

  @NotNull
  private static CharSequence limitCharSequence(@NotNull CharSequence sequence, int limit) {
    return limit == UNLIMITED ? sequence : sequence.subSequence(0, Math.min(limit, sequence.length()));
  }

  @NotNull
  public static CharSequence getTextByBinaryPresentation(@NotNull final byte[] bytes, @NotNull VirtualFile virtualFile) {
    return getTextByBinaryPresentation(bytes, virtualFile, true, true);
  }

  @NotNull
  public static CharSequence getTextByBinaryPresentation(@NotNull byte[] bytes,
                                                         @NotNull VirtualFile virtualFile,
                                                         boolean saveDetectedSeparators,
                                                         boolean saveBOM) {
    DetectResult info = detectInternalCharsetAndSetBOM(virtualFile, bytes, bytes.length, saveBOM, virtualFile.getFileType());
    byte[] bom = info.BOM;
    ConvertResult result = convertBytes(bytes, Math.min(bom == null ? 0 : bom.length, bytes.length), bytes.length,
                                        info.hardCodedCharset);
    if (saveDetectedSeparators) {
      virtualFile.setDetectedLineSeparator(result.lineSeparator);
    }
    return result.text;
  }

  // written in push way to make sure no-one stores the CharSequence because it came from thread-local byte buffers which will be overwritten soon
  @NotNull
  public static FileType processTextFromBinaryPresentationOrNull(@NotNull byte[] bytes, int length,
                                                                 @NotNull VirtualFile virtualFile,
                                                                 boolean saveDetectedSeparators,
                                                                 boolean saveBOM,
                                                                 @NotNull FileType fileType,
                                                                 @NotNull NotNullFunction<? super CharSequence, ? extends FileType> fileTextProcessor) {
    DetectResult info = detectInternalCharsetAndSetBOM(virtualFile, bytes, length, saveBOM, fileType);
    Charset internalCharset = info.hardCodedCharset;
    CharsetToolkit.GuessedEncoding guessed = info.guessed;
    CharSequence toProcess;
    if (internalCharset == null || guessed == CharsetToolkit.GuessedEncoding.BINARY || guessed == CharsetToolkit.GuessedEncoding.INVALID_UTF8) {
      // the charset was not detected so the file is likely binary
      toProcess = null;
    }
    else {
      byte[] bom = info.BOM;
      int BOMEndOffset = Math.min(length, bom == null ? 0 : bom.length);
      ConvertResult result = convertBytes(bytes, BOMEndOffset, length, internalCharset);
      if (saveDetectedSeparators) {
        virtualFile.setDetectedLineSeparator(result.lineSeparator);
      }
      toProcess = result.text;
    }
    return fileTextProcessor.fun(toProcess);
  }

  /**
   * Get detected line separator, if the file never been loaded, is loaded if checkFile parameter is specified.
   *
   * @param file      the file to check
   * @param checkFile if the line separator was not detected before, try to detect it
   * @return the detected line separator or null
   */
  @Nullable
  public static String detectLineSeparator(@NotNull VirtualFile file, boolean checkFile) {
    String lineSeparator = getDetectedLineSeparator(file);
    if (lineSeparator == null && checkFile) {
      try {
        getTextByBinaryPresentation(file.contentsToByteArray(), file);
        lineSeparator = getDetectedLineSeparator(file);
      }
      catch (IOException e) {
        // null will be returned
      }
    }
    return lineSeparator;
  }

  static String getDetectedLineSeparator(@NotNull VirtualFile file) {
    return file.getDetectedLineSeparator();
  }

  @NotNull
  public static CharSequence getTextByBinaryPresentation(@NotNull byte[] bytes, @NotNull Charset charset) {
    Pair.NonNull<Charset, byte[]> pair = getOverriddenCharsetByBOM(bytes, charset);
    byte[] bom = pair.getSecond();

    final ConvertResult result = convertBytes(bytes, Math.min(bom.length, bytes.length), bytes.length, pair.first);
    return result.text;
  }

  @NotNull
  private static ConvertResult convertBytes(@NotNull byte[] bytes,
                                            final int startOffset, int endOffset,
                                            @NotNull Charset internalCharset) {
    assert startOffset >= 0 && startOffset <= endOffset && endOffset <= bytes.length: startOffset + "," + endOffset+": "+bytes.length;
    if (internalCharset instanceof SevenBitCharset || internalCharset == CharsetToolkit.US_ASCII_CHARSET) {
      // optimisation: skip byte-to-char conversion for ascii chars
      return convertLineSeparatorsToSlashN(bytes, startOffset, endOffset);
    }
    
    ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, startOffset, endOffset - startOffset);

    CharBuffer charBuffer;
    try {
      charBuffer = internalCharset.decode(byteBuffer);
    }
    catch (Exception e) {
      // esoteric charsets can throw any kind of exception
      charBuffer = CharBuffer.wrap(ArrayUtil.EMPTY_CHAR_ARRAY);
    }
    return convertLineSeparatorsToSlashN(charBuffer);
  }

  private static class ConvertResult {
    @NotNull private final CharSequence text;
    @Nullable private final String lineSeparator;

    ConvertResult(@NotNull CharSequence text, @Nullable String lineSeparator) {
      this.text = text;
      this.lineSeparator = lineSeparator;
    }
  }

  private static final Key<AutoDetectionReason> CHARSET_WAS_DETECTED_FROM_BYTES = Key.create("CHARSET_WAS_DETECTED_FROM_BYTES");
  @Nullable("null if was not detected, otherwise the reason it was")
  public static AutoDetectionReason getCharsetAutoDetectionReason(@NotNull VirtualFile virtualFile) {
    return virtualFile.getUserData(CHARSET_WAS_DETECTED_FROM_BYTES);
  }

  private static void setCharsetAutoDetectionReason(@NotNull VirtualFile virtualFile,
                                                    @Nullable("null if was not detected, otherwise the reason it was") AutoDetectionReason reason) {
    virtualFile.putUserData(CHARSET_WAS_DETECTED_FROM_BYTES, reason);
  }

  public static void clearCharsetAutoDetectionReason(@NotNull VirtualFile virtualFile) {
    virtualFile.putUserData(CHARSET_WAS_DETECTED_FROM_BYTES, null);
  }
}
