package edu.colorado.thresher.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.ibm.wala.classLoader.ClassFileModule;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrike.shrikeCT.ClassReader;
import com.ibm.wala.shrike.shrikeCT.ConstantPoolParser;
import com.ibm.wala.shrike.shrikeCT.ConstantValueReader;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.core.util.shrike.ShrikeClassReaderHandle;


public class AndroidUtils {

  /**
   * return a map from triggering event handler -> button text for each button 
   * 
   * @param appPath - path to some app's /res/ directory
   */
  
  private final static String DEFAULT_LISTENER = "onClick";

  static class AndroidButton {
    // unique identifier for the button
    final String id;
    // the integer the string id is mapped to
    int intId;
    // name of the method called when the button is clicked (normally onClick, but can be overridden)
    final String eventHandlerName;
    // event handler CGNode's. can be more than one because the manifest only specified a name; 
    // different Activities may provide different implementations for the method of that name 
    Set<CGNode> eventHandlerNodes;
    // name of the string that holds that button label
    final String buttonStringId;
    // text displayed on the button
    String label;

    // abstract memory cell corresponding to the button
    PointerVariable var;
    
    public AndroidButton(String id, String eventHandlerName, String buttonStringId) { 
      this.id = id;
      this.eventHandlerName = eventHandlerName;
      this.buttonStringId = buttonStringId;
      this.eventHandlerNodes = HashSetFactory.make();
    }
    
    public String toString() {
      return "ID: \"" + id + " " + intId + "\" Handler: \"" + eventHandlerName + "\" Label: \"" + label + "\" stringName: \"" + buttonStringId + "\"" 
          + " handler nodes " + Util.printCollection(eventHandlerNodes);
    }
        
    public boolean hasDefaultListener() {
      return DEFAULT_LISTENER.equals(eventHandlerName);
    }
    
  }
  
  private static int nameCounter = 0;
  private static String makeUnknownButtonName() {
	  return "__UNKNOWN" + (nameCounter++);
  }
  
  // TODO: want id -> event handler -> button name mapping
  // TODO: handle volume button (and other hardware buttons), onTouch, e.t.c
  public static Collection<AndroidButton> parseButtonInfo(String appPath) {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    String[] handlerStrs = new String[] { "android:onClick" };
    Set<String> handlerNames = new LinkedHashSet<String>();
    for (int i = 0; i < handlerStrs.length; i++) {
      handlerNames.add(handlerStrs[i]);
    }

    // map from {string id name} -> buttons with that label
    Map<String,List<AndroidButton>> buttonStringMap = HashMapFactory.make();
    // map from {button id} -> button
    Map<String,AndroidButton> buttonIdMap = HashMapFactory.make();
    
    final String BUTTON_ID = "android:id";
    final String HANDLER_INDICATOR = "android:on";  
    final String BUTTON_NAME = "android:text";
    
    Util.Print("app path is " + appPath);
    // see if res is where we expect it to be
    File res = new File(appPath + "/res/");
    if (!res.exists()) {
      appPath = Options.ANDROID_RES;
    	//appPath += "/out/";
      Util.Assert(new File(appPath + "/res/").exists(), "Res directory " + appPath + "/res/ doesn't exist.");
      //Util.Assert(new File(appPath + "res/").exists(), " directory " + appPath + "res/ doesn't exist.");
    }
    
    // for each file in res/layout
    File layoutFolder = new File(appPath + "res/layout");
    //if (!layoutFolder.exists()) layoutFolder = new File(appPath + "out/res/layout");
    
    final File[] layoutFiles = layoutFolder.listFiles();
    for (int f = 0; f < layoutFiles.length; f++) {
      if (!layoutFiles[f].getName().endsWith(".xml")) continue;
      Util.Print("Parsing layout file " + layoutFiles[f]);
      try {
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(layoutFiles[f]);
        Element root = doc.getDocumentElement();
        // get all declared buttons
        NodeList nl = root.getElementsByTagName("Button");
          
        if (nl != null) {
          for (int i = 0; i < nl.getLength(); i++) { // for each button
            Element el = (Element) nl.item(i);
            NamedNodeMap map = el.getAttributes();
            if (map == null) continue;
            String buttonId = null, handlerName = null, buttonStringId = null;
            
            for (int j = 0; j < map.getLength(); j++) { // for each attribute
              Node node = map.item(j);
              String label = node.getNodeName();
              if (label.equals(BUTTON_ID)) {
                buttonId = node.getNodeValue();
                buttonId = buttonId.replace("@+id/", "");
                buttonId = buttonId.replace("@id/", "");
              } else if (handlerNames.contains(label)) {
                handlerName = node.getNodeValue();
              } else if (label.equals(BUTTON_NAME)) {
                buttonStringId = node.getNodeValue();
                buttonStringId = buttonStringId.replace("@string/", "");
              } else if (label.startsWith(HANDLER_INDICATOR)) {
                Util.Unimp("possibly unknown handler " + label);
              }
            }
            if (handlerName == null) {
              // button uses default handler onClick
              handlerName = DEFAULT_LISTENER;
            }
            Util.Assert(buttonId != null, "null id for " + el);
            //Util.Assert(buttonStringId != null); // not all buttons have strings
            if (buttonStringId == null) buttonStringId = makeUnknownButtonName();
            AndroidButton button = new AndroidButton(buttonId, handlerName, buttonStringId);
            
            List<AndroidButton> buttons = buttonStringMap.get(buttonStringId);
            if (buttons == null) {
              buttons = new ArrayList<AndroidButton>();
              buttonStringMap.put(buttonStringId, buttons);
            }
            buttons.add(button);
            buttonIdMap.put(buttonId, button);
          }
        }
      } catch(ParserConfigurationException pce) {
        pce.printStackTrace();
      } catch(SAXException se) {
        se.printStackTrace();
      } catch(IOException ioe) {
        ioe.printStackTrace();
      }
    }
    
    // read button strings from res/values/strings.xml
    try {
      DocumentBuilder db = dbf.newDocumentBuilder();
      File stringsXML = new File(appPath + "res/values/strings.xml");
      //if (!stringsXML.exists()) stringsXML = new File(appPath + "out/res/values/strings.xml");
      Util.Assert(stringsXML.exists(), "couldn't find strings.xml");      
      //Document doc = db.parse(appPath + "res/values/strings.xml");
      Document doc = db.parse(stringsXML.getAbsolutePath());
      Element root = doc.getDocumentElement();
      // get all declared buttons
      NodeList nl = root.getElementsByTagName("string");
                  
      if (nl != null) {
        for (int i = 0; i < nl.getLength(); i++) { // for each string
          Element el = (Element) nl.item(i);
          String name = el.getAttribute("name");
          List<AndroidButton> buttons = buttonStringMap.get(name);           
          if (buttons != null) {
            for (AndroidButton button : buttons) {
              Util.Assert(button.label == null);
              button.label = el.getTextContent();
            }
          }
        }
      }
    } catch(ParserConfigurationException pce) {
      pce.printStackTrace();
    } catch(SAXException se) {
      se.printStackTrace();
    } catch(IOException ioe) {
      ioe.printStackTrace();
    } 
    
    // read button id's from res/values/public.xml, if it exists
    File publicXML = new File(appPath + "res/values/public.xml");
    if (publicXML.exists()) {
      try {
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(publicXML);
        Element root = doc.getDocumentElement();
        // get all button id's
        NodeList nl = root.getElementsByTagName("public");
                    
        if (nl != null) {
          for (int i = 0; i < nl.getLength(); i++) { // for each string
            Element el = (Element) nl.item(i);
            String type = el.getAttribute("type");
            if (type.equals("id")) {
              String name = el.getAttribute("name");
              AndroidButton button = buttonIdMap.get(name);   
              if (button != null) {
                // found the button; now get and parse its id
                String id = el.getAttribute("id");
                int radix = 10;
                if (id.startsWith("0x")) {
                  // Java doesn't like parsing hexes that start with 0x. strip it out
                  id = id.substring(2, id.length());
                  radix = 16; // indicate that this is a hex value
                }
                Util.Assert(!id.contains("0x"));
                // get integer id assigned to button
                int intValue = Integer.parseInt(id, radix);
                button.intId = intValue;
              }
            }
          }
        }
      } catch(ParserConfigurationException pce) {
        pce.printStackTrace();
      } catch(SAXException se) {
        se.printStackTrace();
      } catch(IOException ioe) {
        ioe.printStackTrace();
      } 
    } else {
      // otherwise, try to parse them from the .class files generated from R directly
      parseIntIds(buttonIdMap, appPath);
    }
    
    // make sure we've found the labels and int id's for all buttons
    for (AndroidButton button : buttonIdMap.values()) {
      //Util.Assert(button.label != null, "No label for button " + button);
      Util.Assert(button.intId != 0, "No id for button " + button);
    }
    
    return buttonIdMap.values();
  }

  // copied from Shrike
	public static final byte CONSTANT_Integer = 3;
	
	static void buildSyntacticCG(String appPath) {
	  String path = appPath + "bin/classes";
	  final File binDir = new File(path);
	  Util.Assert(binDir.exists(), "can't find " + path);
	  final Collection<File> binFiles = Util.listFilesRec(binDir);
	  for (File f : binFiles) {
	    if (f.toString().endsWith(".class")) {
		  try {
		    ClassFileModule module = new ClassFileModule(f, null);
		    ShrikeClassReaderHandle handle = new ShrikeClassReaderHandle(module);
			ClassReader reader = handle.get();
			
			String clazz = reader.getName();
			Util.Print("class is " + clazz);
			ClassReader.AttrIterator iter = new ClassReader.AttrIterator();

			
			for (int i = 0; i < reader.getMethodCount(); i++) {
			  String name = reader.getMethodName(i);
			  String signature = reader.getMethodType(i);
			  //Util.Print("method " + name);
			  //Util.Print("sig " + signature);
				
			  reader.initMethodAttributeIterator(i, iter);
			  // iterate over code for method
			}
		  }
		  catch (InvalidClassFileException e) {
		    System.err.println("bad class file " + e);
		  }
		}
	  }
	}
	
   /**
	* find R$id.class, parse out button ID's from the constant pool using Shrike
	*/
	static void parseIntIds(Map<String,AndroidButton> buttonIdMap, String appPath) {
		String path = appPath + "bin";
		final File binDir = new File(path);
		Util.Assert(binDir.exists(), "can't find " + path);
		final Collection<File> binFiles = Util.listFilesRec(binDir);
		for (File f : binFiles) {
			String fileName = f.getName().toString();
			if (fileName.equals("R$id.class")) {
				// TODO: assert that class is an inner class of R.java
				try {
				    ClassFileModule module = new ClassFileModule(f, null);
					ShrikeClassReaderHandle handle = new ShrikeClassReaderHandle(module);
					ClassReader reader = handle.get();
					ConstantPoolParser cpParser = reader.getCP();
					ClassReader.AttrIterator iter = new ClassReader.AttrIterator();

					// for each field declared in the class
					for (int i = 0; i < reader.getFieldCount(); i++) {
						reader.initFieldAttributeIterator(i, iter);
						String name = reader.getFieldName(i);
						for (; iter.isValid(); iter.advance()) {
							reader.initFieldAttributeIterator(i, iter);
							if (iter.getName().equals("ConstantValue")) {
								ConstantValueReader cv = new ConstantValueReader(iter);
								AndroidButton button = buttonIdMap.get(name);
								int cpIndex = cv.getValueCPIndex();
								// if we have a button with the name of this field and the field type is an int,
								// then this field is the button ID
								if (button != null && cpParser.getItemType(cpIndex) == CONSTANT_Integer) {
									int fieldVal = cpParser.getCPInt(cpIndex);
									button.intId = fieldVal;
								}
							}
						}
					}				
				}
				catch (InvalidClassFileException e) {
					System.err.println("bad class file " + e);
				}
			}
		}
	}
}
