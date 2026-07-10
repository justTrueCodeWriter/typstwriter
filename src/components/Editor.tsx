import React, { useRef } from 'react';
import {
  View,
  TextInput,
  StyleSheet,
  Platform,
} from 'react-native';

interface EditorProps {
  value: string;
  onChangeText: (text: string) => void;
  editable?: boolean;
}

export function Editor({ value, onChangeText, editable = true }: EditorProps) {
  const textInputRef = useRef<TextInput>(null);

  return (
    <View style={styles.container}>
      <TextInput
        ref={textInputRef}
        style={styles.editor}
        multiline
        value={value}
        onChangeText={onChangeText}
        editable={editable}
        placeholder="Enter Typst code..."
        placeholderTextColor="#999"
        autoCorrect={false}
        spellCheck={false}
        autoCapitalize="none"
        textAlignVertical="top"
        selectTextOnFocus={false}
        keyboardType="default"
        returnKeyType="default"
        blurOnSubmit={false}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  editor: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#ccc',
    padding: 12,
    fontFamily: Platform.OS === 'android' ? 'monospace' : 'Courier',
    fontSize: 14,
    lineHeight: 20,
    color: '#000',
    backgroundColor: '#fff',
    textAlignVertical: 'top',
  },
});
