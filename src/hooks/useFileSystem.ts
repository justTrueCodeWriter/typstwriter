import { useState, useCallback } from 'react';
import RNFS from 'react-native-fs';
import DocumentPicker from 'react-native-document-picker';
import { Document } from '../types';

const DOCUMENTS_DIR = `${RNFS.DocumentDirectoryPath}/typst-documents`;
const FONTS_DIR = `${RNFS.DocumentDirectoryPath}/typst-fonts`;

export function useFileSystem() {
  const [currentDocument, setCurrentDocument] = useState<Document | null>(null);
  const [recentDocuments, setRecentDocuments] = useState<Document[]>([]);

  const ensureDirectories = useCallback(async () => {
    const exists = await RNFS.exists(DOCUMENTS_DIR);
    if (!exists) {
      await RNFS.mkdir(DOCUMENTS_DIR);
    }
    const fontsExists = await RNFS.exists(FONTS_DIR);
    if (!fontsExists) {
      await RNFS.mkdir(FONTS_DIR);
    }
  }, []);

  const openFile = useCallback(async (): Promise<Document | null> => {
    try {
      const result = await DocumentPicker.pickSingle({
        type: [DocumentPicker.types.plainText],
      });

      const content = await RNFS.readFile(result.uri, 'utf8');
      const document: Document = {
        id: Date.now().toString(),
        name: result.name || 'untitled.typ',
        path: result.uri,
        content,
        lastModified: new Date(),
      };

      setCurrentDocument(document);
      return document;
    } catch (err) {
      if (!DocumentPicker.isCancel(err)) {
        console.error('Error opening file:', err);
      }
      return null;
    }
  }, []);

  const saveFile = useCallback(async (content: string, path?: string): Promise<string> => {
    await ensureDirectories();

    const fileName = currentDocument?.name || `document-${Date.now()}.typ`;
    const filePath = path || `${DOCUMENTS_DIR}/${fileName}`;

    await RNFS.writeFile(filePath, content, 'utf8');

    if (currentDocument) {
      setCurrentDocument({
        ...currentDocument,
        content,
        lastModified: new Date(),
      });
    }

    return filePath;
  }, [currentDocument, ensureDirectories]);

  const saveAs = useCallback(async (content: string): Promise<string | null> => {
    try {
      const result = await DocumentPicker.pickDirectory();
      if (!result) return null;

      const fileName = currentDocument?.name || `document-${Date.now()}.typ`;
      const filePath = `${result.uri}/${fileName}`;

      await RNFS.writeFile(filePath, content, 'utf8');
      return filePath;
    } catch (err) {
      console.error('Error in Save As:', err);
      return null;
    }
  }, [currentDocument]);

  const exportFile = useCallback(async (
    data: ArrayBuffer,
    format: 'pdf' | 'html',
    fileName?: string
  ): Promise<string | null> => {
    try {
      const base64 = arrayBufferToBase64(data);
      const name = fileName || currentDocument?.name.replace('.typ', '') || 'output';
      const exportPath = `${RNFS.DocumentDirectoryPath}/${name}.${format}`;

      await RNFS.writeFile(exportPath, base64, 'base64');
      return exportPath;
    } catch (err) {
      console.error('Error exporting file:', err);
      return null;
    }
  }, [currentDocument]);

  const getFontPath = useCallback(async (): Promise<string> => {
    await ensureDirectories();
    return FONTS_DIR;
  }, [ensureDirectories]);

  const listFonts = useCallback(async (): Promise<string[]> => {
    const exists = await RNFS.exists(FONTS_DIR);
    if (!exists) return [];

    const files = await RNFS.readDir(FONTS_DIR);
    return files
      .filter(f => /\.(ttf|otf|woff|woff2)$/i.test(f.name))
      .map(f => f.path);
  }, []);

  return {
    currentDocument,
    setCurrentDocument,
    recentDocuments,
    openFile,
    saveFile,
    saveAs,
    exportFile,
    getFontPath,
    listFonts,
  };
}

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}
