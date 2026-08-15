# Release notes

Overzicht van wijzigingen per versie van de Coworker-plugin.

## Unreleased
- De gebruikersprompt van **CoWorker vragen** ondersteunt `{{pv:...}}`- en
  `{{doc:/...}}`-placeholders, zodat zaakgegevens in de tekst verwerkt kunnen worden.
- **CoWorker-antwoord afwachten** kan velden uit een JSON-antwoord wegschrijven naar
  procesvariabelen (`pv:`) en het zaakdossier (`doc:`) via het nieuwe veld
  **Antwoord verwerken**. Mislukt dat, dan gaat het proces door met een uitleg in de
  procesvariabele `coworkerMappingError`.
- **CoWorker vragen** kan één document meesturen via het nieuwe veld **Document**
  (een Valtimo resource-id, meestal `pv:resourceId`). Maximale grootte instelbaar met
  `valtimo.coworker.max-document-size` (standaard 10 MB).

## 0.1.0
Eerste release

## 0.2.0
Value resolvers for receive process link

## 0.3.0
Support for amqps and username/password authentication
