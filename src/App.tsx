import React, { useState, useCallback, useEffect } from 'react';
import {
  View,
  Alert,
  StyleSheet,
  StatusBar,
} from 'react-native';
import { Editor } from './components/Editor';
import { Toolbar } from './components/Toolbar';
import { useTypstCompiler } from './hooks/useTypstCompiler';
import { useFileSystem } from './hooks/useFileSystem';
import { DEFAULT_SOURCE } from './constants';
import Share from 'react-native-share';

export default function App() {
  const [source, setSource] = useState(DEFAULT_SOURCE);
  const { compile, isCompiling, error, clearError } = useTypstCompiler();
  const fileSystem = useFileSystem();

  useEffect(() => {
    fileSystem.ensureDirectories();
  }, []);

  const handleNew = useCallback(() => {
    Alert.alert(
      'New Document',
      'Create a new document? Unsaved changes will be lost.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'New',
          onPress: () => {
            setSource(DEFAULT_SOURCE);
            fileSystem.setCurrentDocument(null);
          },
        },
      ]
    );
  }, [fileSystem]);

  const handleOpen = useCallback(async () => {
    const doc = await fileSystem.openFile();
    if (doc) {
      setSource(doc.content);
    }
  }, [fileSystem]);

  const handleSave = useCallback(async () => {
    if (!fileSystem.currentDocument) return;
    try {
      await fileSystem.saveFile(source);
      Alert.alert('Saved', 'Document saved successfully');
    } catch (err) {
      Alert.alert('Error', 'Failed to save document');
    }
  }, [source, fileSystem]);

  const handleSaveAs = useCallback(async () => {
    try {
      await fileSystem.saveAs(source);
      Alert.alert('Saved', 'Document saved successfully');
    } catch (err) {
      Alert.alert('Error', 'Failed to save document');
    }
  }, [source, fileSystem]);

  const handleExport = useCallback(async (format: 'pdf' | 'html') => {
    clearError();
    const fontPath = await fileSystem.getFontPath();
    const result = await compile(source, format);

    if (result) {
      const filePath = await fileSystem.exportFile(result, format);
      if (filePath) {
        try {
          await Share.open({
            url: `file://${filePath}`,
            type: format === 'pdf' ? 'application/pdf' : 'text/html',
            title: `Export as ${format.toUpperCase()}`,
          });
        } catch (shareErr) {
          Alert.alert('Exported', `File saved to: ${filePath}`);
        }
      }
    } else if (error) {
      Alert.alert('Compilation Error', error);
    }
  }, [source, compile, error, clearError, fileSystem]);

  return (
    <View style={styles.container}>
      <StatusBar
        barStyle="dark-content"
        backgroundColor="#fff"
      />

      <Editor
        value={source}
        onChangeText={setSource}
      />

      <Toolbar
        onNew={handleNew}
        onOpen={handleOpen}
        onSave={handleSave}
        onSaveAs={handleSaveAs}
        onExportPDF={() => handleExport('pdf')}
        onExportHTML={() => handleExport('html')}
        isCompiling={isCompiling}
        hasDocument={!!fileSystem.currentDocument || source !== DEFAULT_SOURCE}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
  },
});
