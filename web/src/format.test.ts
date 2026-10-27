import { formatPaise, formatPaiseCompact, humanize, parseRupeesToPaise } from './format';

describe('formatPaise', () => {
  it.each([
    [0, '₹0.00'],
    [5, '₹0.05'],
    [123456, '₹1,234.56'],
    [1234567890, '₹1,23,45,678.90'],
    [-4050, '-₹40.50'],
  ])('%i paise -> %s', (minor, text) => {
    expect(formatPaise(minor)).toBe(text);
  });

  it('refuses fractional minor units', () => {
    expect(() => formatPaise(10.5)).toThrow(/integer/);
  });

  it('labels other currencies by code', () => {
    expect(formatPaise(100, 'USD')).toBe('USD 1.00');
  });
});

describe('parseRupeesToPaise', () => {
  it.each([
    ['1500', 150000],
    ['1,500.5', 150050],
    ['₹ 0.07', 7],
    ['12.345', null],
    ['abc', null],
  ])('%s -> %s', (input, paise) => {
    expect(parseRupeesToPaise(input)).toBe(paise);
  });
});

it('formats compact axis labels and humanizes codes', () => {
  expect(formatPaiseCompact(12_345_600)).toBe('₹1.2L');
  expect(formatPaiseCompact(5_00_00_00_000)).toBe('₹5.0Cr');
  expect(humanize('MISSING_IN_BANK')).toBe('Missing In Bank');
});
