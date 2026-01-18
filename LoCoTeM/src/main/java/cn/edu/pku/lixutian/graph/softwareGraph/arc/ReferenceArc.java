package cn.edu.pku.lixutian.graph.softwareGraph.arc;

public abstract class ReferenceArc extends Arc {
    public ReferenceArc(String label) {
        super(label);
    }

    public static abstract class UseReference extends ReferenceArc {
        public UseReference(String label) {
            super(label);
        }

        public static class FieldUse extends ReferenceArc.UseReference {
            public FieldUse() {
                super("FieldUse");
            }
        }

        public static class FieldDef extends ReferenceArc.UseReference {
            public FieldDef() {
                super("FieldDef");
            }
        }

        public static class EnumUse extends ReferenceArc.UseReference {
            public EnumUse() {
                super("EnumUse");
            }
        }

        public static class AnnotationMemberUse extends ReferenceArc.UseReference {
            public AnnotationMemberUse() {
                super("AnnotationMemberUse");
            }
        }
    }

    public static abstract class FieldReference extends ReferenceArc {
        public FieldReference(String label) {
            super(label);
        }

        public static class FieldType extends FieldReference {
            public FieldType() {
                super("FieldType");
            }
        }

        public static class AnnotationMemberType extends FieldReference {
            public AnnotationMemberType() {
                super("AnnotationMemberType");
            }
        }
    }

    public static abstract class MethodReference extends ReferenceArc {
        public MethodReference(String label) {
            super(label);
        }

        public static class ParameterType extends MethodReference {
            public ParameterType() {
                super("ParameterType");
            }
        }

        public static class ReturnType extends MethodReference {
            public ReturnType() {
                super("ReturnType");
            }
        }

        public static class VariableType extends MethodReference {
            public VariableType() {
                super("VariableType");
            }
        }
    }

    public static abstract class AnnotationReference extends ReferenceArc {
        public AnnotationReference(String label) {
            super(label);
        }

        public static class AnnotationUse extends AnnotationReference{
            public AnnotationUse() {
                super("AnnotationUse");
            }
        }
    }
}