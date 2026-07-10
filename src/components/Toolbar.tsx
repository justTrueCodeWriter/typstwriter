import React from 'react';
import {
  View,
  TouchableOpacity,
  Text,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';

interface ToolbarProps {
  onNew: () => void;
  onOpen: () => void;
  onSave: () => void;
  onSaveAs: () => void;
  onExportPDF: () => void;
  onExportHTML: () => void;
  isCompiling: boolean;
  hasDocument: boolean;
}

export function Toolbar({
  onNew,
  onOpen,
  onSave,
  onSaveAs,
  onExportPDF,
  onExportHTML,
  isCompiling,
  hasDocument,
}: ToolbarProps) {
  return (
    <View style={styles.container}>
      <View style={styles.row}>
        <TouchableOpacity style={styles.button} onPress={onNew}>
          <Text style={styles.buttonText}>New</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.button} onPress={onOpen}>
          <Text style={styles.buttonText}>Open</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.button, !hasDocument && styles.disabled]}
          onPress={onSave}
          disabled={!hasDocument}
        >
          <Text style={styles.buttonText}>Save</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.button, !hasDocument && styles.disabled]}
          onPress={onSaveAs}
          disabled={!hasDocument}
        >
          <Text style={styles.buttonText}>Save As</Text>
        </TouchableOpacity>
      </View>

      <View style={styles.row}>
        <TouchableOpacity
          style={[styles.button, styles.exportButton]}
          onPress={onExportPDF}
          disabled={isCompiling || !hasDocument}
        >
          {isCompiling ? (
            <ActivityIndicator size="small" color="#fff" />
          ) : (
            <Text style={styles.exportButtonText}>Export PDF</Text>
          )}
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.button, styles.exportButton]}
          onPress={onExportHTML}
          disabled={isCompiling || !hasDocument}
        >
          {isCompiling ? (
            <ActivityIndicator size="small" color="#fff" />
          ) : (
            <Text style={styles.exportButtonText}>Export HTML</Text>
          )}
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    backgroundColor: '#f5f5f5',
    borderTopWidth: 1,
    borderTopColor: '#ccc',
    paddingVertical: 8,
    paddingHorizontal: 12,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    marginBottom: 8,
  },
  button: {
    backgroundColor: '#333',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 4,
    minWidth: 60,
    alignItems: 'center',
  },
  buttonText: {
    color: '#fff',
    fontSize: 12,
    fontWeight: 'bold',
  },
  exportButton: {
    backgroundColor: '#000',
  },
  exportButtonText: {
    color: '#fff',
    fontSize: 12,
    fontWeight: 'bold',
  },
  disabled: {
    opacity: 0.5,
  },
});
