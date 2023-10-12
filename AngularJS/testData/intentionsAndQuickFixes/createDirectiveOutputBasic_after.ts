import {Component, Directive, EventEmitter, Output} from '@angular/core';

@Directive({
    selector: '[test]',
    standalone: true,
})
export class TestDirective {
    @Output() foo = new EventEmitter<{ bar: number }>();<caret>

}

@Component({
    selector: 'app-root',
    imports: [TestDirective],
    standalone: true,
    template: `
      <div test (foo)="check($event)"></div>`
})
export class AppComponent {

  check(value: {bar: number}) {

  }

}
