package test;

class SomeEnum {
  String rawValue;

  public SomeEnum(String rawValue) {
    this.rawValue = rawValue;
  }

  static SomeEnum NORTH = new SomeEnum("NORTH");

  static class Unknown__ extends SomeEnum {
    public Unknown__(String rawValue) {
      super(rawValue);
    }
  }
}