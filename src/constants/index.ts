export const COLORS = {
  background: '#ffffff',
  text: '#000000',
  border: '#cccccc',
  button: '#333333',
  buttonDisabled: '#999999',
};

export const DEFAULT_SOURCE = `#set page(width: 10cm, height: auto)
#set heading(numbering: "1.")

= Hello from E-ink!

This is a **Typst** document compiled on your device.

#let fib(n) = (
  if n <= 2 { 1 }
  else { fib(n - 1) + fib(n - 2) }

The 10th Fibonacci number is: #fib(10)
`;
