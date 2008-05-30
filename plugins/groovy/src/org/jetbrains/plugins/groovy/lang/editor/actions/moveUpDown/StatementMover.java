/*
 * Copyright 2000-2008 JetBrains s.r.o.
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

package org.jetbrains.plugins.groovy.lang.editor.actions.moveUpDown;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.PsiDocumentManagerImpl;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrCodeBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrOpenBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinitionBody;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.refactoring.GroovyRefactoringUtil;

/**
 * @author ilyas
 */
public class StatementMover extends LineMover {

  private static final Logger LOG = Logger.getInstance("#org.jetbrains.plugins.groovy.lang.editor.actions.moveUpDown.StatementMover");

  public StatementMover(boolean down) {
    super(down);
  }

  protected void beforeMove(final Editor editor) {
    super.beforeMove(editor);
  }

  protected boolean checkAvailable(Editor editor, PsiFile file) {
    final boolean available = super.checkAvailable(editor, file);
    if (!available) return false;
    LineRange range = toMove;

    range = expandLineRangeToCoverPsiElements(range, editor, file);
    if (range == null) return false;
    final int startOffset = editor.logicalPositionToOffset(new LogicalPosition(range.startLine, 0));
    final int endOffset = editor.logicalPositionToOffset(new LogicalPosition(range.endLine, 0));
    final PsiElement[] statements = GroovyRefactoringUtil.findStatementsInRange(file, startOffset, endOffset);
    if (statements.length == 0) return false;
    range.firstElement = statements[0];
    range.lastElement = statements[statements.length - 1];

    if (!checkMovingInsideOutside(file, editor, range)) {
      toMove2 = null;
      return true;
    }
    return true;
  }

  private static LineRange expandLineRangeToCoverPsiElements(final LineRange range, Editor editor, final PsiFile file) {
    Pair<PsiElement, PsiElement> psiRange = getElementRange(editor, file, range);
    if (psiRange == null) return null;
    final PsiElement parent = PsiTreeUtil.findCommonParent(psiRange.getFirst(), psiRange.getSecond());
    Pair<PsiElement, PsiElement> elementRange = getElementRange(parent, psiRange.getFirst(), psiRange.getSecond());
    if (elementRange == null) return null;
    int endOffset = elementRange.getSecond().getTextRange().getEndOffset();
    Document document = editor.getDocument();
    if (endOffset > document.getTextLength()) {
      LOG.assertTrue(!PsiDocumentManager.getInstance(file.getProject()).isUncommited(document));
      LOG.assertTrue(PsiDocumentManagerImpl.checkConsistency(file, document));
    }
    int endLine;
    if (endOffset == document.getTextLength()) {
      endLine = document.getLineCount();
    } else {
      endLine = editor.offsetToLogicalPosition(endOffset).line;
      endLine = Math.min(endLine, document.getLineCount());
    }
    int startLine = Math.min(range.startLine, editor.offsetToLogicalPosition(elementRange.getFirst().getTextOffset()).line);
    endLine = Math.max(endLine, range.endLine);
    return new LineRange(startLine, endLine);
  }

  private boolean checkMovingInsideOutside(PsiFile file, final Editor editor, final LineRange result) {
    final int offset = editor.getCaretModel().getOffset();

    PsiElement guard = file.getViewProvider().findElementAt(offset);
    if (guard == null) return false;

    // cannot move in/outside method/class/closure/comment
    guard = PsiTreeUtil.getParentOfType(guard, GrMethod.class, GrTypeDefinition.class, PsiComment.class, GrClosableBlock.class);

    if (!calcInsertOffset(file, editor, result)) return false;
    int insertOffset = isDown ? getLineStartSafeOffset(editor.getDocument(), toMove2.endLine) : editor.getDocument().getLineStartOffset(toMove2.startLine);
    PsiElement newGuard = PsiTreeUtil.getParentOfType(guard, GrMethod.class, GrTypeDefinition.class, PsiComment.class, GrClosableBlock.class);
    if (newGuard == guard && isInside(insertOffset, newGuard) == isInside(offset, guard)) return true;

    return false;
  }

  private static boolean isInside(final int offset, final PsiElement guard) {
    if (guard == null) return false;

    TextRange inside = null;
    if (guard instanceof GrMethod) {
      GrOpenBlock block = ((GrMethod) guard).getBlock();
      if (block != null) inside = block.getTextRange();
    } else if (guard instanceof GrClosableBlock) {
      inside = guard.getTextRange();
    } else if (guard instanceof GrTypeDefinition) {
      GrTypeDefinitionBody body = ((GrTypeDefinition) guard).getBody();
      if (body != null && body.getLBrace() != null)
        inside = new TextRange(body.getLBrace().getTextOffset(), body.getTextRange().getEndOffset());
    }
    return inside != null && inside.contains(offset);
  }

  private boolean calcInsertOffset(PsiFile file, final Editor editor, LineRange range) {
    int line = isDown ? range.endLine + 1 : range.startLine - 1;
    int startLine = isDown ? range.endLine : range.startLine - 1;
    if (line < 0 || startLine < 0) return false;
    while (true) {
      final int offset = editor.logicalPositionToOffset(new LogicalPosition(line, 0));
      PsiElement element = firstNonWhiteElement(offset, file, true);

      while (element != null && !(element instanceof PsiFile)) {
        if (!element.getTextRange().grown(-1).shiftRight(1).contains(offset)) {
          boolean found = false;
          if ((element instanceof GrStatement || element instanceof PsiComment)
              && statementCanBePlacedAlong(element)) {
            found = true;
            if (!(element.getParent() instanceof GrCodeBlock)) {
            }
          } else if (element.getNode() != null &&
              element.getNode().getElementType() == GroovyTokenTypes.mRCURLY) {
            // before code block closing brace
            found = true;
          }
          if (found) {
            toMove = range;
            int endLine = line;
            if (startLine > endLine) {
              int tmp = endLine;
              endLine = startLine;
              startLine = tmp;
            }
            toMove2 = isDown ? new LineRange(startLine, endLine) : new LineRange(startLine, endLine + 1);
            return true;
          }
        }
        element = element.getParent();
      }
      line += isDown ? 1 : -1;
      if (line == 0 || line >= editor.getDocument().getLineCount()) {
        return false;
      }
    }
  }

  private static boolean statementCanBePlacedAlong(final PsiElement element) {
    if (element instanceof GrBlockStatement) return false;
    final PsiElement parent = element.getParent();
    if (parent instanceof GrCodeBlock) return true;
    if (parent instanceof GrIfStatement &&
        (element == ((GrIfStatement) parent).getThenBranch() || element == ((GrIfStatement) parent).getElseBranch())) {
      return true;
    }
    if (parent instanceof GrWhileStatement && element == ((GrWhileStatement) parent).getBody()) {
      return true;
    }
    if (parent instanceof GrForStatement && element == ((GrForStatement) parent).getBody()) {
      return true;
    }
    // know nothing about that
    return false;
  }

}
