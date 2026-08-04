package com.mycode.graph.softwareGraph.arc;


public abstract class ClassArc extends Arc {

    public ClassArc(String label) {
        super(label);
    }

    public abstract static class ParentArc extends ClassArc {
        public ParentArc(String label) {
            super(label);
        }

        public static class Extends extends ParentArc {
            public Extends() {
                super("Extends");
            }
        }

        public static class Implements extends ParentArc {
            public Implements() {
                super("Implements");
            }
        }
    }

    public static abstract class InnerArc extends ClassArc {
        protected InnerArc(String label) {
            super(label);
        }

        public static class InnerEnum extends InnerArc {
            public InnerEnum() {
                super("InnerEnum");
            }
        }

        public static class InnerClass extends InnerArc {
            public InnerClass() {
                super("InnerClass");
            }
        }

        public static class InnerAnnotation extends InnerArc {
            public InnerAnnotation() {
                super("InnerAnnotation");
            }
        }
    }

    public abstract static class MemberArc extends ClassArc {
        protected MemberArc(String label) {
            super(label);
        }

        public static class AnnotationMember extends MemberArc {
            public AnnotationMember() {
                super("AnnotationMember");
            }
        }

        public static class FieldMember extends MemberArc {
            public FieldMember() {
                super("FieldMember");
            }
        }

        public static class MethodMember extends MemberArc {
            public MethodMember() {
                super("MethodMember");
            }
        }

        public static class ConstructorMember extends MemberArc {
            public ConstructorMember() {
                super("ConstructorMember");
            }
        }

        public abstract static class InitializerMember extends MemberArc {
            protected InitializerMember(String label) {
                super(label);
            }

            public static class StaticInitializer extends InitializerMember {
                public StaticInitializer() {
                    super("StaticInitializer");
                }
            }

            public static class NormalInitializer extends InitializerMember {
                public NormalInitializer() {
                    super("NormalInitializer");
                }
            }
        }


    }


}