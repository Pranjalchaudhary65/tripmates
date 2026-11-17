import type { ReactNode } from 'react';

/** Wiring a control needs so assistive technology reads its error and hint with it. */
export interface FieldControlProps {
  id: string;
  'aria-invalid': boolean;
  'aria-describedby': string | undefined;
}

export interface FormFieldProps {
  id: string;
  label: string;
  error?: string | undefined;
  hint?: string | undefined;
  children: (control: FieldControlProps) => ReactNode;
}

export function FormField({ id, label, error, hint, children }: FormFieldProps) {
  const hintId = hint === undefined ? undefined : `${id}-hint`;
  const errorId = error === undefined ? undefined : `${id}-error`;
  const describedBy = [hintId, errorId].filter((value) => value !== undefined).join(' ');

  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-slate-800">
        {label}
      </label>
      {children({ id, 'aria-invalid': error !== undefined, 'aria-describedby': describedBy || undefined })}
      {hint !== undefined && (
        <p id={hintId} className="mt-1 text-xs text-slate-500">
          {hint}
        </p>
      )}
      {error !== undefined && (
        <p id={errorId} className="mt-1 text-sm text-red-700">
          {error}
        </p>
      )}
    </div>
  );
}

/** Shared look for inputs and selects; the red border follows aria-invalid. */
export const inputClassName =
  'mt-1 block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-slate-900 shadow-sm ' +
  'focus:border-teal-600 focus:outline-none focus:ring-2 focus:ring-teal-600/30 ' +
  'aria-[invalid=true]:border-red-500 disabled:bg-slate-100';
