/* LanguageTool, a natural language style checker 
 * Copyright (C) 2005 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package de.danielnaber.languagetool.openoffice;

/** OpenOffice 3.x Integration
 * 
 * @author Marcin Miłkowski
 */
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;

import javax.swing.JOptionPane;

import com.sun.star.awt.XControl;
import com.sun.star.awt.XListBox;
import com.sun.star.awt.XControlContainer;
import com.sun.star.awt.XButton;
import com.sun.star.awt.XWindow;
import com.sun.star.awt.XControlModel;
import com.sun.star.awt.XWindowPeer;
import com.sun.star.awt.tree.*;
import com.sun.star.frame.XDesktop;
import com.sun.star.frame.XModel;
import com.sun.star.lang.IllegalArgumentException;
import com.sun.star.lang.Locale;
import com.sun.star.lang.XComponent;
import com.sun.star.lang.XMultiComponentFactory;
import com.sun.star.lang.XServiceInfo;
import com.sun.star.lang.XSingleComponentFactory;
import com.sun.star.lib.uno.helper.Factory;
import com.sun.star.lib.uno.helper.WeakBase;
import com.sun.star.linguistic2.GrammarCheckingResult;
import com.sun.star.linguistic2.SingleGrammarError;
import com.sun.star.linguistic2.XGrammarChecker;
import com.sun.star.linguistic2.XLinguServiceEventBroadcaster;
import com.sun.star.linguistic2.XLinguServiceEventListener;
import com.sun.star.registry.XRegistryKey;
import com.sun.star.task.XJobExecutor;
import com.sun.star.text.XTextDocument;
import com.sun.star.text.XTextViewCursor;
import com.sun.star.uno.UnoRuntime;
import com.sun.star.uno.XComponentContext;
import com.sun.star.beans.UnknownPropertyException;
import com.sun.star.beans.XPropertySet;
import com.sun.star.container.NoSuchElementException;
import com.sun.star.container.XEnumeration;
import com.sun.star.lang.WrappedTargetException;
import com.sun.star.text.XTextRange;
import com.sun.star.container.XNameAccess;
import com.sun.star.deployment.XPackageInformationProvider;
import com.sun.star.awt.XDialogProvider;
import com.sun.star.awt.XDialog;
import com.sun.star.text.XTextViewCursorSupplier;


import de.danielnaber.languagetool.JLanguageTool;
import de.danielnaber.languagetool.Language;
import de.danielnaber.languagetool.gui.Configuration;
import de.danielnaber.languagetool.gui.Tools;
import de.danielnaber.languagetool.rules.Rule;
import de.danielnaber.languagetool.rules.RuleMatch;
import de.danielnaber.languagetool.tools.StringTools;

public class Main extends WeakBase implements 
XJobExecutor, XServiceInfo, XGrammarChecker,
XLinguServiceEventBroadcaster {

  private Configuration config;
  private JLanguageTool langTool; 
  private Language docLanguage;

  private XTextViewCursor xViewCursor;

  private List <XLinguServiceEventListener> xEventListeners;

  /**
   * Make another instance of JLanguageTool
   * and assign it to langTool if true.
   */
  private boolean recheck = false;

  /** Service name required by the OOo API && our own name.
   * 
   */
  private static final String[] SERVICE_NAMES = {
    "com.sun.star.linguistic2.GrammarChecker",
    "de.danielnaber.languagetool.openoffice.Main"
  };

//use a different name than the stand-alone version to avoid conflicts:
  private static final String CONFIG_FILE = ".languagetool-ooo.cfg";

  private static final ResourceBundle MESSAGES = JLanguageTool.getMessageBundle();

  private XComponentContext xContext;

  /** Document ID. The document IDs can be used 
   * for storing the document-level state (e.g., for
   * document-level spelling consistency).
   * 
   */
  private int myDocID = -1;

  public Main(final XComponentContext xCompContext) {
    try {
      changeContext(xCompContext);
      final File homeDir = getHomeDir();
      config = new Configuration(homeDir, CONFIG_FILE);
      xEventListeners = new ArrayList<XLinguServiceEventListener>();
    } catch (final Throwable e) {
      writeError(e);
      e.printStackTrace();
    }
  }

  public void changeContext(final XComponentContext xCompContext) {
      xContext = xCompContext;          
  }
  
  private XComponent getxComponent() {
    try {
    final XMultiComponentFactory xMCF = xContext.getServiceManager();
    final Object desktop = xMCF.createInstanceWithContext("com.sun.star.frame.Desktop", xContext);
    final XDesktop xDesktop = (XDesktop) UnoRuntime.queryInterface(XDesktop.class, desktop);      
    final XComponent xComponent = xDesktop.getCurrentComponent();
    return xComponent;
   } catch (final Throwable e) {
    writeError(e);
    e.printStackTrace();
    return null;
   }
  }  

  /**
   * Checks the language under the cursor. Used for opening the
   * configuration dialog. 
   * @return Language - the language under the visible cursor.
   */
  private Language getLanguage() {
    final XComponent xComponent = getxComponent(); 
    if (xComponent == null) {
      return Language.ENGLISH; // for testing with local main() method only
    }
    Locale charLocale;
    XPropertySet xCursorProps;
    try {     
      final XModel model = 
        (XModel) UnoRuntime.queryInterface(XModel.class, xComponent);
      final XTextViewCursorSupplier 
      xViewCursorSupplier = (XTextViewCursorSupplier) 
      UnoRuntime.queryInterface(XTextViewCursorSupplier.class, 
          model.getCurrentController());
      final XTextViewCursor xCursor = 
        xViewCursorSupplier.getViewCursor();
      if (xCursor.isCollapsed()) { //no text selection
        xCursorProps = (XPropertySet) 
        UnoRuntime.queryInterface(XPropertySet.class,
            xCursor);        
      } else { //text is selected, need to create another cursor  
              //as multiple languages can occur here - we care only 
              //about character under the cursor, which might be wrong
              //but it applies only to the checking dialog to be removed 
        xCursorProps = (XPropertySet) 
        UnoRuntime.queryInterface(XPropertySet.class,
            xCursor.getText().
            createTextCursorByRange(xCursor.getStart()));              
      }
      final Object obj = xCursorProps.getPropertyValue("CharLocale");
      if (obj == null) {
        return Language.ENGLISH; // fallback
      } 
      charLocale = (Locale) obj; 
      boolean langIsSupported = false;
      for (Language element : Language.LANGUAGES) {
        if (element.getShortName().equals(charLocale.Language)) {
          langIsSupported = true;
          break;
        }
      }
      if (!langIsSupported) {
        // FIXME: i18n
        JOptionPane.showMessageDialog(null, "Error: Sorry, the document language '" +charLocale.Language+ 
        "' is not supported by LanguageTool.");
        return null;
      }
    } catch (final UnknownPropertyException e) {
      throw new RuntimeException(e);
    } catch (final WrappedTargetException e) {
      throw new RuntimeException(e);
    }
    return Language.getLanguageForShortName(charLocale.Language);
  }

  /** Runs the grammar checker on paragraph text.
   * @param docID int - document ID
   * @param paraText - paragraph text
   * @param locale Locale - the text Locale  
   * @param startOfSentencePos int start of sentence position
   * @param suggEndOfSentencePos int end of sentence position
   * @param aLanguagePortions - lengths of language portions with a given locale
   * @param aLanguagePortionsLocales - locales of language portions
   * @return GrammarCheckingResult containing the results of the check.
   * @throws IllegalArgumentException (not really, LT simply returns
   * the GrammarCheckingResult with the values supplied)
   */
  public final GrammarCheckingResult doGrammarChecking(final int docID,  
      final String paraText, final Locale locale, 
      final int startOfSentencePos, final int suggEndOfSentencePos,
      final int[] aLanguagePortions, 
      final Locale[] aLanguagePortionsLocales) 
  throws IllegalArgumentException {    
    final GrammarCheckingResult paRes = new GrammarCheckingResult();
    paRes.nEndOfSentencePos = suggEndOfSentencePos - startOfSentencePos;    
    paRes.xGrammarChecker = this;
    paRes.aLocale = locale;                    
    paRes.nDocumentId = docID;    
    paRes.aText = paraText;

    if (paraText == null) {
      return paRes;
    } else {
      paRes.nEndOfSentencePos = paraText.length();
    }

    if (!"".equals(paraText)) { 
//  TODO: process different language fragments in a paragraph 
//  according to their language (currently assumed = locale)
//  note: this is not yet implemented in the API     

    if (hasLocale(locale)) {
      //caching the instance of LT
      if (!Language.getLanguageForShortName(locale.Language).equals(docLanguage)
          || langTool == null
          || recheck) {
        docLanguage = Language.getLanguageForShortName(locale.Language);
        if (docLanguage == null) {
          return paRes;
        }                
        try {
          langTool = new JLanguageTool(docLanguage, config.getMotherTongue());
          langTool.activateDefaultPatternRules();
          langTool.activateDefaultFalseFriendRules();
          recheck = false;
        } catch (final Exception exception) {
          showError(exception);
        }
      }

      if (config.getDisabledRuleIds() != null) {
        for (final String id : config.getDisabledRuleIds()) {                    
          langTool.disableRule(id);
        }
      }
      final Set<String> disabledCategories = config.getDisabledCategoryNames();
      if (disabledCategories != null) {
        for (final String categoryName : disabledCategories) {          
          langTool.disableCategory(categoryName);
        }
      }      
      try {        
        final List<RuleMatch> ruleMatches = langTool.check(paraText);
        if (!ruleMatches.isEmpty()) {          
          final SingleGrammarError[] errorArray = new SingleGrammarError[ruleMatches.size()];;
          int i = 0;
          for (final RuleMatch myRuleMatch : ruleMatches) {
            errorArray[i] = createOOoError(locale, myRuleMatch);
            i++;
          }
          paRes.aGrammarErrors = errorArray;
        }
      } catch (final IOException exception) {
        showError(exception);
      }      
    }
    }
    return paRes;    
  }

  /** Creates a SingleGrammarError object for use in OOo.
   * @param locale Locale - the text Locale
   * @param myMatch ruleMatch - LT rule match
   * @return SingleGrammarError - object for OOo checker integration
   */
  private SingleGrammarError createOOoError(final Locale locale, 
      final RuleMatch myMatch) {
    final SingleGrammarError aError = new SingleGrammarError();
    aError.nErrorType = com.sun.star.text.TextMarkupType.GRAMMAR;    
    //  the API currently has no support for formatting text in comments 
    final String comment =  myMatch.getMessage().
    replaceAll("<suggestion>", "\"").
    replaceAll("</suggestion>", "\"");     
    aError.aFullComment = comment;    
    //  we don't support two kinds of comments
    aError.aShortComment = aError.aFullComment; 
    aError.aSuggestions = myMatch.getSuggestedReplacements()
    .toArray(new String [myMatch.getSuggestedReplacements().size()]);
    aError.nErrorLevel = 0; // severity level, we don't use it
    aError.nErrorStart = myMatch.getFromPos();      
    aError.nErrorLength = myMatch.getToPos() - myMatch.getFromPos();
    aError.aNewLocale = locale;
    return aError;
  }

  /**
   * Called when the document check is finished.
   * @param oldDocID - the ID of the document already checked
   * @throws IllegalArgumentException in case oldDocID is not a 
   * valid myDocID.
   */
  public void endDocument(final int oldDocID) throws IllegalArgumentException {
    if (myDocID == oldDocID) {
      myDocID = -1;
    }
  }

  /**
   * Called to clear the paragraph state. Not used yet in our implementation.
   * 
   * @param docID - the ID of the document already checked
   *  valid myDocID.
   */
  public void endParagraph(final int docID) {
    // TODO Auto-generated method stub
  }

  /** LT has an options dialog box,
   * so we return true.
   * @return true
   * */
  public final boolean hasOptionsDialog() {
    return true;
  }

  /** LT does not support spell-checking,
   * so we return false.
   * @return false
   */
  public final boolean isSpellChecker() {
    return false;
  }

  /** Runs LT options dialog box.
   **/
  public final void runOptionsDialog() {        
    final Language lang = getLanguage();
    if (lang == null) {
      return;
    }    
    final ConfigThread configThread = new ConfigThread(lang, config, this);
    configThread.start();
  }

  /**
   * Called to setup the doc state via ID.
   * @param docID - the doc ID
   * @throws IllegalArgumentException in case docID is not a 
   *  valid document ID.
   **/
  public final void startDocument(final int docID) 
  throws IllegalArgumentException {    
    myDocID = docID;
    docLanguage = getLanguage();
    try {
      langTool = new JLanguageTool(docLanguage, config.getMotherTongue());
      langTool.activateDefaultPatternRules();
      langTool.activateDefaultFalseFriendRules();
    } catch (final Exception exception) {
      showError(exception);    
    }
  }

  /**
   * Called to setup the paragraph state in a doc with some ID.
   * Not yet implemented (probably will be implemented in the future).
   * @param docID - the doc ID
   * @throws IllegalArgumentException in case docID is not a 
   *  valid myDocID.
   **/
  public void startParagraph(final int docID) throws IllegalArgumentException {
    // TODO Auto-generated method stub
  }

  /**
   * @return An array of Locales supported by LT.
   */
  public final Locale[] getLocales() {
    int dims = 0;
    for (final Language element : Language.LANGUAGES) {
      dims += element.getCountryVariants().length;
    }
    final Locale[] aLocales = new Locale[dims];
    int cnt = 0;
    for (final Language element : Language.LANGUAGES) {
      for (final String variant : element.getCountryVariants()) {
        aLocales[cnt] = new Locale(element.getShortName(), variant, "");
        cnt++; 
      }
    }
    return aLocales;
  }

  /** @return true if LT supports
   * the language of a given locale.
   * @param locale The Locale to check.
   */
  public final boolean hasLocale(final Locale locale) {    
    for (final Language element : Language.LANGUAGES) {
      if (element.getShortName().equals(locale.Language)) {
        return true;
      }
    }
    return false;
  }


  /**
   * Add a listener that allow re-checking the document after
   * changing the options in the configuration dialog box.
   * @param xLinEvLis - the listener to be added
   * @return true if listener is non-null and has been added, false otherwise.
   */
  public final boolean addLinguServiceEventListener(
      final XLinguServiceEventListener xLinEvLis) {
    if (xLinEvLis == null) {
      return false;
    } else {
      xEventListeners.add(xLinEvLis);
      return true;      
    }
  }

  /** Remove a listener from the event listeners list.
   * @param xLinEvLis - the listener to be removed
   * @return true if listener is non-null and has been removed, false otherwise.
   */
  public final boolean removeLinguServiceEventListener(
      final XLinguServiceEventListener xLinEvLis) {
    if (xLinEvLis == null) {
      return false;
    } else {
      if (xEventListeners.contains(xLinEvLis)) {
        xEventListeners.remove(xLinEvLis);
        return true;
      } else { 
        return false;
      }

    }
  }

  /**
   * Inform listener (grammar checking iterator)
   * that options have changed and the doc should be rechecked.
   *
   */
  public final void resetDocument() {
    if (!xEventListeners.isEmpty()) {
      for (final XLinguServiceEventListener xEvLis : xEventListeners) {
        if (xEvLis != null) {
          final com.sun.star.linguistic2.LinguServiceEvent 
          xEvent = new com.sun.star.linguistic2.LinguServiceEvent();
          xEvent.nEvent = 
            com.sun.star.linguistic2.LinguServiceEventFlags.GRAMMAR_CHECK_AGAIN;
          xEvLis.processLinguServiceEvent(xEvent);
        }
      }
      recheck = true;
    }
  }

  public String[] getSupportedServiceNames() {
    return getServiceNames();
  }

  public static String[] getServiceNames() {
    return SERVICE_NAMES;
  }

  public boolean supportsService(final String sServiceName) {
    for (final String sName : SERVICE_NAMES) {
      if (sServiceName.equals(sName)) {
        return true; 
      }
    }
    return false;
  }

  public String getImplementationName() {
    return Main.class.getName();
  }

  public static XSingleComponentFactory __getComponentFactory(final String sImplName) {
    SingletonFactory xFactory = null;
    if (sImplName.equals(Main.class.getName())) {
      xFactory = new SingletonFactory();
    }
    return xFactory;
  }

  public static boolean __writeRegistryServiceInfo(final XRegistryKey regKey) {
    return Factory.writeRegistryServiceInfo(Main.class.getName(), Main.getServiceNames(), regKey);
  }

  public void trigger(final String sEvent) {
    if (!javaVersionOkay()) {
      return;
    }
    try {
      if (sEvent.equals("execute")) {
        //try out the new XFlatParagraph interface...
        final TextToCheck textToCheck = getText();
        checkText(textToCheck);
      } else if (sEvent.equals("test_new_win")) {
//      TODO: make this a separate config dialog class        
        final XMultiComponentFactory xMCF = xContext.getServiceManager();      
//      get PackageInformationProvider from ComponentContext
        final XNameAccess xNameAccess = (XNameAccess) UnoRuntime.queryInterface(
            XNameAccess.class, xContext);
        final Object oPIP = xNameAccess.getByName("/singletons/com.sun.star.deployment.PackageInformationProvider");
        final XPackageInformationProvider xPIP = (XPackageInformationProvider) UnoRuntime.queryInterface(
            XPackageInformationProvider.class, oPIP);
//      get the url of the directory extension installed
        final String sPackageURL = 
          xPIP.getPackageLocation("org.openoffice.languagetool.oxt");
        final String sDialogURL = sPackageURL + "/Options.xdl";

//      dialog provider to make a dialog
        final Object oDialogProvider = xMCF.createInstanceWithContext(
            "com.sun.star.awt.DialogProvider", xContext);
        final XDialogProvider xDialogProv = (XDialogProvider) UnoRuntime.queryInterface(
            XDialogProvider.class, oDialogProvider);
        final XDialog xDialog = xDialogProv.createDialog(sDialogURL);
        final XControlContainer xDlgContainer = (XControlContainer) UnoRuntime.queryInterface(XControlContainer.class, xDialog);
        final XControl xListControl = xDlgContainer.getControl("LanguageList");
        final XListBox xListBox = (XListBox) UnoRuntime.queryInterface(XListBox.class, xListControl);
        for (short i = 0; i < Language.LANGUAGES.length - 1; i++) {
          xListBox.addItem(
              Language.LANGUAGES[i].getTranslatedName(MESSAGES), i);
        }
        final XButton xOKButton = (XButton) UnoRuntime.queryInterface(XButton.class, xDlgContainer.getControl("OK_Button"));
        xOKButton.setLabel(StringTools.getOOoLabel(MESSAGES.getString("guiOKButton")));
        final XButton xCancelButton = (XButton) UnoRuntime.queryInterface(XButton.class, xDlgContainer.getControl("Cancel_Button"));
        xCancelButton.setLabel(StringTools.getOOoLabel(MESSAGES.getString("guiCancelButton")));

        final XControl xControlTree = xDlgContainer.getControl("Rules");
        final XControlModel xTreeModel = xControlTree.getModel();

        final Object xTreeData = xMCF.createInstanceWithContext(
            "com.sun.star.awt.tree.MutableTreeDataModel", xContext);
        final XMutableTreeDataModel mxTreeDataModel = (XMutableTreeDataModel) UnoRuntime.queryInterface(
            XMutableTreeDataModel.class, xTreeData);

        final XMutableTreeNode xNode = mxTreeDataModel.createNode("Rules", false);

        xNode.appendChild(mxTreeDataModel.createNode("Misc", false));
        xNode.appendChild(mxTreeDataModel.createNode("Punctuation", false));

        mxTreeDataModel.setRoot(xNode);

        final XPropertySet xTreeModelProperty = (XPropertySet) UnoRuntime.queryInterface(
            XPropertySet.class, xTreeModel);
        xTreeModelProperty.setPropertyValue("DataModel", mxTreeDataModel);

        xNode.setDataValue("test2");
        xNode.setExpandedGraphicURL(sPackageURL + "triangle_down.png");
        xNode.setCollapsedGraphicURL(sPackageURL + "triangle_right.png");

        final short nResult = xDialog.execute();

      } else if (sEvent.equals("configure")) {                
        runOptionsDialog();        
      } else if (sEvent.equals("about")) {
        final AboutDialogThread aboutthread = new AboutDialogThread(MESSAGES);
        aboutthread.start();
      } else {
        System.err.println("Sorry, don't know what to do, sEvent = " + sEvent);
      }        
    } catch (final Throwable e) {
      showError(e);
    }
  }

  private void checkText(final TextToCheck textToCheck) {
    if (textToCheck == null) {      
      return;
    }
    final Language docLanguage = getLanguage();
    if (docLanguage == null) {
      return;
    }
    final ProgressDialog progressDialog = new ProgressDialog(MESSAGES);
    final CheckerThread checkerThread = new CheckerThread(textToCheck.paragraphs, docLanguage, config, 
        progressDialog);
    checkerThread.start();
    while (true) {
      if (checkerThread.done()) {
        break;
      }
      try {
        Thread.sleep(100);
      } catch (final InterruptedException e) {
        // nothing
      }
    }
    progressDialog.close();

    final List<CheckedParagraph> checkedParagraphs = checkerThread.getRuleMatches();
    // TODO: why must these be wrapped in threads to avoid focus problems?
    if (checkedParagraphs.isEmpty()) {
      String msg;
      final String translatedLangName = MESSAGES.getString(docLanguage.getShortName());
      if (textToCheck.isSelection) {
        msg = Tools.makeTexti18n(MESSAGES, "guiNoErrorsFoundSelectedText", new String[] {translatedLangName});  
      } else {
        msg = Tools.makeTexti18n(MESSAGES, "guiNoErrorsFound", new String[] {translatedLangName});  
      }
      final DialogThread dt = new DialogThread(msg);
      dt.start();
      // TODO: display number of active rules etc?
    } else {
      ResultDialogThread dialog;      
      final XTextDocument xTextDoc = (XTextDocument) UnoRuntime.queryInterface(XTextDocument.class, getxComponent());
      if (textToCheck.isSelection) {
        dialog = new ResultDialogThread(config,
            checkerThread.getLanguageTool().getAllRules(),
            xTextDoc, checkedParagraphs, xViewCursor, textToCheck);
      } else {
        dialog = new ResultDialogThread(config,
            checkerThread.getLanguageTool().getAllRules(),
            xTextDoc, checkedParagraphs, null, null);
      }
      dialog.start();
    }
  }

  //TODO: remove as soon the native OOo dialog is flawless
  @Deprecated
  private TextToCheck getText() {
    final XTextDocument xTextDoc = (XTextDocument) UnoRuntime.queryInterface(XTextDocument.class, getxComponent());    
    com.sun.star.container.XEnumerationAccess xParaAccess = (com.sun.star.container.XEnumerationAccess) UnoRuntime
    .queryInterface(com.sun.star.container.XEnumerationAccess.class, xTextDoc.getText());
    if (xParaAccess == null) {
      System.err.println("xParaAccess == null");
      return new TextToCheck(new ArrayList<String>(), false);
    }

    XModel xModel = (XModel)UnoRuntime.queryInterface(XModel.class, xTextDoc);
    if (xModel == null) {
      // FIXME: i18n
      DialogThread dt = new DialogThread("Sorry, only text documents are supported");
      dt.start();
      return null;
    }    
    XTextViewCursorSupplier xViewCursorSupplier = 
      (XTextViewCursorSupplier)UnoRuntime.queryInterface(XTextViewCursorSupplier.class, 
          xModel.getCurrentController()); 
    xViewCursor = xViewCursorSupplier.getViewCursor();
    String textToCheck = xViewCursor.getString();     // user's current selection
    if (textToCheck.equals("")) {     // no selection = check complete text
      //System.err.println("check complete text");
    } else {
      //System.err.println("check selected text");
      List<String> l = new ArrayList<String>();
      // FIXME: if footnotes with a number greater than "9" occur in the selected text
      // they mess up the error marking in OOoDialog because they appear as "10" etc.
      // but the code assumes we need to use goRight() once per character...
      l.add(textToCheck);
      return new TextToCheck(l, true);
    }

    List<String> paragraphs = new ArrayList<String>();
    try {
      for (com.sun.star.container.XEnumeration xParaEnum = xParaAccess.createEnumeration(); xParaEnum.hasMoreElements();) {
        Object para = xParaEnum.nextElement();
        String paraString = getParagraphContent(para);
        if (paraString == null) {
          paragraphs.add("");
        } else {
          paragraphs.add(paraString);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return new TextToCheck(paragraphs, false);
  }


  private boolean javaVersionOkay() {
    final String version = System.getProperty("java.version");
    if (version != null && (version.startsWith("1.0") || version.startsWith("1.1")
        || version.startsWith("1.2") || version.startsWith("1.3") || version.startsWith("1.4"))) {
      final DialogThread dt = new DialogThread("Error: LanguageTool requires Java 1.5 or later. Current version: " + version);
      dt.start();
      return false;
    }    
    return true;
  }

  static void showError(final Throwable e) {
    String msg = "An error has occured:\n" + e.toString() + "\nStacktrace:\n";
    final StackTraceElement[] elem = e.getStackTrace();
    for (final StackTraceElement element : elem) {
      msg += element.toString() + "\n";
    }
    final DialogThread dt = new DialogThread(msg);
    dt.start();
    e.printStackTrace();
    throw new RuntimeException(e);
  }

  private void writeError(final Throwable e) {
    FileWriter fw;
    try {
      fw = new FileWriter("languagetool.log");
      fw.write(e.toString() + "\r\n");
      final StackTraceElement[] el = e.getStackTrace();
      for (final StackTraceElement element : el) {
        fw.write(element.toString() + "\r\n");
      }
      fw.close();
    } catch (final IOException e1) {
      e1.printStackTrace();
    }
  }

  private File getHomeDir() {
    final String homeDir = System.getProperty("user.home");
    if (homeDir == null) {
      throw new RuntimeException("Could not get home directory");
    }
    return new File(homeDir);
  }

//TODO: remove this method when spell-checking dialog window is available
  //and bug-free :-/
  static String getParagraphContent(final Object para) throws NoSuchElementException, WrappedTargetException, UnknownPropertyException {
    if (para == null) {
      return null;
    }
    final com.sun.star.container.XEnumerationAccess xPortionAccess = (com.sun.star.container.XEnumerationAccess) UnoRuntime
    .queryInterface(com.sun.star.container.XEnumerationAccess.class, para);
    if (xPortionAccess == null) {
      System.err.println("xPortionAccess is null");
      return null;
    }
    final StringBuilder sb = new StringBuilder();
    for (final XEnumeration portionEnum = xPortionAccess.createEnumeration(); portionEnum.hasMoreElements();) {
      final Object textPortion = portionEnum.nextElement();
      final XPropertySet textProps = (XPropertySet) UnoRuntime.queryInterface(XPropertySet.class, textPortion);
      final String type = (String)textProps.getPropertyValue("TextPortionType");
      if ("Footnote".equals(type) || "DocumentIndexMark".equals(type)) {
        // a footnote reference appears as one character in the text. we don't use a whitespace
        // because we don't want to trigger the "no whitespace before comma" rule in this case:
        // my footnoteÂ¹, foo bar
        sb.append("1");
      } else {
        final XTextRange xtr = (XTextRange) UnoRuntime.queryInterface(XTextRange.class, textPortion);
        sb.append(xtr.getString());
      }
    }
    return sb.toString();
  }

  class AboutDialogThread extends Thread {

    private ResourceBundle messages;

    AboutDialogThread(final ResourceBundle messages) {
      this.messages = messages;
    }

    @Override
    public void run() {
      final XModel model = (XModel)UnoRuntime.queryInterface(XModel.class, getxComponent());
      final XWindow parentWindow = model.getCurrentController().getFrame().getContainerWindow();
      final XWindowPeer parentWindowPeer = (XWindowPeer) UnoRuntime.queryInterface(XWindowPeer.class, parentWindow);
      final OOoAboutDialog about = 
        new OOoAboutDialog(messages, parentWindowPeer);
      about.show();
    }
  }


}

//TODO: remove these classes step by step, they're becoming obsolete

class DialogThread extends Thread {
  private String text;

  DialogThread(final String text) {
    this.text = text;
  }

  @Override
  public void run() {
    JOptionPane.showMessageDialog(null, text);
  }
}

class ResultDialogThread extends Thread {

  private Configuration configuration;
  private List<Rule> rules;
  private XTextDocument xTextDoc;
  private List<CheckedParagraph> checkedParagraphs;
  private XTextViewCursor xViewCursor;
  private TextToCheck textTocheck;

  ResultDialogThread(final Configuration configuration, final List<Rule> rules, final XTextDocument xTextDoc,
      final List<CheckedParagraph> checkedParagraphs, final XTextViewCursor xViewCursor,
      final TextToCheck textTocheck) {
    this.configuration = configuration;
    this.rules = rules;
    this.xTextDoc = xTextDoc;
    this.checkedParagraphs = checkedParagraphs;
    this.xViewCursor = xViewCursor;
    this.textTocheck = textTocheck;
  }

  @Override
  public void run() {
    OOoDialog dialog;
    if (xViewCursor == null) {
      dialog = new OOoDialog(configuration, rules, xTextDoc, checkedParagraphs);
    } else {
      dialog = new OOoDialog(configuration, rules, xTextDoc, checkedParagraphs, xViewCursor, textTocheck);
    }
    dialog.show();
  }

}
class TextToCheck {

  List<String> paragraphs;
  boolean isSelection;

  TextToCheck(final List<String> paragraphs, final boolean isSelection) {
    this.paragraphs = paragraphs;
    this.isSelection = isSelection;
  }
}  

