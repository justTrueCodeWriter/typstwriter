import { useState, useCallback } from 'react';
import { ExportFormat, CompileResult } from '../types';

// Декларация глобальной JSI функции
declare global {
  function compileTypstAsync(
    source: string,
    format: string,
    fontPath?: string
  ): Promise<CompileResult>;
}

export function useTypstCompiler(fontPath?: string) {
  const [isCompiling, setIsCompiling] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const compile = useCallback(async (
    source: string,
    format: ExportFormat
  ): Promise<ArrayBuffer | null> => {
    if (!source.trim()) {
      setError('Source code is empty');
      return null;
    }

    setIsCompiling(true);
    setError(null);

    try {
      const result = await global.compileTypstAsync(
        source,
        format,
        fontPath
      );
      return result.data;
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Compilation failed';
      setError(message);
      return null;
    } finally {
      setIsCompiling(false);
    }
  }, [fontPath]);

  const clearError = useCallback(() => setError(null), []);

  return { compile, isCompiling, error, clearError };
}
