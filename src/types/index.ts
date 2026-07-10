export type ExportFormat = 'pdf' | 'html';

export interface CompileResult {
  data: ArrayBuffer;
  format: ExportFormat;
}

export interface Document {
  id: string;
  name: string;
  path: string;
  content: string;
  lastModified: Date;
}

export interface AppSettings {
  fontPath: string;
  autoSave: boolean;
  exportFormat: ExportFormat;
}
